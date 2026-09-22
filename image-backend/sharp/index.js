/**
 * A stand-in for sharp, for the one platform sharp has no binding for
 *
 * `attachment-local` is the only consumer, and it uses sharp for two things: reading an image's
 * facts, and proving the bytes decode completely. Both happen on every image the model reads, and
 * both are fatal on a device where `require('sharp')` throws - so without this file no picture
 * reaches the model on Android at all. libvips cannot be built for bionic here, but the facts the
 * store verifies are readable from a PNG header and the decode proof is a zlib inflate away, which
 * is what this module does instead of shipping pixels through a codec.
 *
 * What it deliberately does not do is re-encode. Every terminal encoding call throws, so an image
 * that has to be resized or converted says so instead of quietly returning something else. The
 * screenshots this device produces are already inside the limits - the capture path scales them -
 * so the store's pass-through takes them byte for byte, and a picture that does need work is
 * reported as unsupported rather than mangled.
 *
 * Supported by `metadata()` and `raw().toBuffer()`: PNG, colour types 0 (grey), 2 (RGB), 4 (grey
 * with alpha) and 6 (RGBA), 8 or 16 bits per channel, not interlaced. Everything else - JPEG,
 * WebP, GIF, palette or interlaced PNG - is refused, which the store reports as unsupported image
 * data, exactly as it would for a corrupt file.
 */

'use strict'

const { inflateSync } = require('node:zlib')

/** The eight bytes every PNG starts with */
const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** Channels per pixel for the colour types this module decodes */
const CHANNELS = { 0: 1, 2: 3, 4: 2, 6: 4 }

/** Colour types that carry an alpha channel of their own */
const ALPHA_TYPES = new Set([4, 6])

/** Colour types libvips reads as greyscale, which is what its `space` reports for them */
const GREY_TYPES = new Set([0, 4])

/**
 * Chunks that count as descriptive metadata
 *
 * This is the same set `carriesRetainedMetadata` asks sharp about: a colour-space hint like sRGB
 * or sBIT is how the file says "these pixels are sRGB", not something attached to them, and libvips
 * reports no profile for either
 */
const METADATA_CHUNKS = new Set(['iCCP', 'eXIf', 'tEXt', 'zTXt', 'iTXt'])

/**
 * The factory the attachment store calls, with sharp's own call shape
 * @param {Uint8Array|Buffer} data complete encoded image bytes.
 * @returns {Pipeline} a pipeline that answers facts and decodes, or throws when asked to encode.
 */
function sharp(data) {
  return new Pipeline(Buffer.from(data.buffer, data.byteOffset, data.byteLength))
}

/** The head of one parsed PNG: what the header says, plus the pieces a decode needs */
function parse(data) {
  if (data.length < SIGNATURE.length || !data.subarray(0, SIGNATURE.length).equals(SIGNATURE)) {
    throw new Error('unsupported image data: not a PNG')
  }
  let offset = SIGNATURE.length
  let header
  let frames
  let metadata
  const parts = []
  while (offset + 8 <= data.length) {
    const length = data.readUInt32BE(offset)
    const type = data.toString('latin1', offset + 4, offset + 8)
    const body = data.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      header = {
        width: body.readUInt32BE(0),
        height: body.readUInt32BE(4),
        bitDepth: body[8],
        colourType: body[9],
        interlace: body[12],
      }
    } else if (type === 'IDAT') {
      parts.push(body)
    } else if (type === 'acTL') {
      frames = body.readUInt32BE(0)
    } else if (METADATA_CHUNKS.has(type)) {
      metadata = type
    }
    offset += 12 + length
    if (type === 'IEND') break
  }
  if (header === undefined) throw new Error('unsupported image data: no PNG header')
  if (header.interlace !== 0) throw new Error('unsupported image data: interlaced PNG')
  if (header.bitDepth !== 8 && header.bitDepth !== 16) {
    throw new Error(`unsupported image data: ${header.bitDepth}-bit PNG`)
  }
  if (CHANNELS[header.colourType] === undefined) {
    throw new Error(`unsupported image data: PNG colour type ${header.colourType}`)
  }
  return { header, frames, metadata, parts }
}

/**
 * Decode one parsed PNG's pixels, which is the proof the store asks for before it trusts a file
 * @param {ReturnType<typeof parse>} parsed the header, frames and compressed parts.
 * @returns {Buffer} the raw pixels, in the file's own channel count and sample width.
 */
function decode(parsed) {
  const { header, parts } = parsed
  const channels = CHANNELS[header.colourType]
  const sampleBytes = header.bitDepth / 8
  const stride = header.width * channels * sampleBytes
  // One filter byte in front of every scanline, and a 16-bit PNG keeps its samples big-endian
  const raw = inflateSync(Buffer.concat(parts))
  const expected = (stride + 1) * header.height
  if (raw.length < expected) {
    throw new Error(`unsupported image data: PNG has ${raw.length} bytes of pixels, expected ${expected}`)
  }
  const pixels = Buffer.alloc(stride * header.height)
  const bpp = channels * sampleBytes
  for (let row = 0; row < header.height; row += 1) {
    const filter = raw[row * (stride + 1)]
    const source = raw.subarray(row * (stride + 1) + 1, row * (stride + 1) + 1 + stride)
    const target = pixels.subarray(row * stride, (row + 1) * stride)
    const previous = row === 0 ? undefined : pixels.subarray((row - 1) * stride, row * stride)
    unfilter(filter, source, target, previous, bpp)
  }
  return pixels
}

/**
 * Reverse one PNG scanline filter in place
 * @param {number} filter the filter type the encoder chose for this row.
 * @param {Buffer} source the filtered bytes.
 * @param {Buffer} target where the reconstructed bytes go.
 * @param {Buffer|undefined} previous the reconstructed row above, absent for the first row.
 * @param {number} bpp bytes per complete pixel, which is how far back the left predictor reaches.
 */
function unfilter(filter, source, target, previous, bpp) {
  for (let index = 0; index < source.length; index += 1) {
    const left = index >= bpp ? target[index - bpp] : 0
    const above = previous === undefined ? 0 : previous[index]
    const upperLeft = previous === undefined || index < bpp ? 0 : previous[index - bpp]
    let value = source[index]
    if (filter === 1) value += left
    else if (filter === 2) value += above
    else if (filter === 3) value += (left + above) >> 1
    else if (filter === 4) value += paeth(left, above, upperLeft)
    else if (filter !== 0) throw new Error(`unsupported image data: PNG filter ${filter}`)
    target[index] = value & 0xff
  }
}

/** The PNG spec's Paeth predictor */
function paeth(left, above, upperLeft) {
  const estimate = left + above - upperLeft
  const toLeft = Math.abs(estimate - left)
  const toAbove = Math.abs(estimate - above)
  const toUpperLeft = Math.abs(estimate - upperLeft)
  if (toLeft <= toAbove && toLeft <= toUpperLeft) return left
  return toAbove <= toUpperLeft ? above : upperLeft
}

/** One image's pipeline: sharp's chainable shape, with the encoders refusing honestly */
class Pipeline {
  constructor(data) {
    this.data = data
  }

  /** The facts the attachment store records, read from the header */
  async metadata() {
    const parsed = parse(this.data)
    const { header } = parsed
    return {
      format: 'png',
      width: header.width,
      height: header.height,
      depth: header.bitDepth === 16 ? 'ushort' : 'uchar',
      space: GREY_TYPES.has(header.colourType) ? 'b-w' : header.bitDepth === 16 ? 'rgb16' : 'srgb',
      hasAlpha: ALPHA_TYPES.has(header.colourType),
      pages: parsed.frames ?? 1,
      ...parsed.metadata === undefined ? {} : { comments: [parsed.metadata] },
    }
  }

  /** Decode the pixels, which is how the store proves the bytes are whole */
  raw() {
    return new Raw(this.data)
  }

  /** No transform is applied: an image that needs one is refused at the encoder below */
  rotate() {
    return this
  }

  toColourspace() {
    return this
  }

  resize() {
    return this
  }

  clone() {
    return new Pipeline(this.data)
  }

  webp() {
    return new Encoder(this.data, 'WebP')
  }

  jpeg() {
    return new Encoder(this.data, 'JPEG')
  }
}

/** The terminal of an encoding chain, which this device cannot honour */
class Encoder {
  constructor(data, format) {
    this.data = data
    this.format = format
  }

  async toBuffer() {
    // Decoding first keeps the message true: a file that is broken is reported as broken, and only
    // a file that is fine is refused for the reason that actually applies to it
    decode(parse(this.data))
    throw new Error(
      `this device has no ${this.format} encoder: the image is fine, but it has to arrive already`
      + ' inside the size the model request accepts, because nothing here can re-encode it',
    )
  }
}

/** A decoded raster, in the shape `image.raw().toBuffer()` returns */
class Raw {
  constructor(data) {
    this.data = data
  }

  async toBuffer() {
    return decode(parse(this.data))
  }
}

module.exports = sharp
