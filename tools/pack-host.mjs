/**
 * Materialise the dsh host tree that the APK ships
 *
 * The host cannot run from the pnpm workspace: workspace packages are linked into
 * `node_modules` as symlinks that only exist in a checkout, and the APK carries neither the
 * checkout nor the links. dsh already owns the supported alternative - pack every published
 * package into an npm tarball, then install those tarballs into an empty consumer, which is
 * what `scripts/release/verify-packed-install.ts` exercises in upstream CI - so this script
 * drives exactly that and leaves a plain, symlink-free `node_modules` behind
 *
 * usage: node tools/pack-host.mjs --dsh <checkout> --out <dir> [--install] [--skip-build]
 */

import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

/** Where the two release families are packed, relative to the checkout */
const PACK_ROOT = 'dist/lw-host-pack'

/**
 * Build output directories that outlive their sources
 *
 * tsdown runs with `clean: false`, so a chunk from an earlier build stays in `lib/` after its
 * source changed, and every package's `files` glob picks up `lib/*.js` wholesale - a stale chunk
 * can ship an eager native import this repository exists to remove
 */
const BUILD_OUTPUTS = [
  'packages/*/*/lib',
  'apps/*/lib',
  'vendor/*/lib',
  'native/system/packages/*/lib',
]

/** Run one command in the checkout, inheriting stdio so build progress stays visible */
function run(command, args, cwd) {
  execFileSync(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' })
}

/** Expand a pattern whose only wildcard is a whole path segment, keeping directories that exist */
function expand(root, pattern) {
  let current = [root]
  for (const segment of pattern.split('/')) {
    const next = []
    for (const directory of current) {
      if (segment !== '*') {
        next.push(join(directory, segment))
        continue
      }
      if (!existsSync(directory)) continue
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isDirectory()) next.push(join(directory, entry.name))
      }
    }
    current = next
  }
  return current.filter(existsSync)
}

/** Delete every stale build output so the rebuild cannot repack a chunk from a previous one */
function cleanBuildOutputs(root) {
  let removed = 0
  for (const pattern of BUILD_OUTPUTS) {
    for (const directory of expand(root, pattern)) {
      rmSync(directory, { recursive: true, force: true })
      removed += 1
    }
  }
  console.log(`pack-host: removed ${String(removed)} stale build output(s) under ${root}`)
}

/** Every tarball under one directory, keyed by the package name it declares */
function packedDependencies(directory) {
  const packages = new Map()
  for (const filename of readdirSync(directory).filter(name => name.endsWith('.tgz')).sort()) {
    const manifest = execFileSync('tar', ['-xOzf', join(directory, filename), 'package/package.json'], {
      encoding: 'utf8',
    })
    const { name, version } = JSON.parse(manifest)
    packages.set(name, { url: pathToFileURL(join(directory, filename)).href, version })
  }
  return packages
}

/** Recursive file count and byte total, for the size report */
function measure(root) {
  let files = 0
  let bytes = 0
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      if (entry.isDirectory()) walk(path)
      else {
        files += 1
        bytes += statSync(path).size
      }
    }
  }
  walk(root)
  return { files, bytes }
}

const { values } = parseArgs({
  options: {
    dsh: { type: 'string' },
    out: { type: 'string' },
    install: { type: 'boolean', default: false },
    'skip-build': { type: 'boolean', default: false },
  },
  allowPositionals: false,
})
if (values.dsh === undefined || values.out === undefined) {
  throw new Error('usage: node tools/pack-host.mjs --dsh <checkout> --out <dir> [--install] [--skip-build]')
}

const dsh = resolve(values.dsh)
const out = resolve(values.out)
if (!existsSync(join(dsh, 'pnpm-workspace.yaml'))) throw new Error(`${dsh} is not a dsh checkout`)
if (values.install) run('pnpm', ['install', '--frozen-lockfile'], dsh)

// The pack step refuses artifacts that were not produced by an official client build, so the
// build profile is not optional here even though a plain `pnpm run build` would boot locally
if (!values['skip-build']) {
  cleanBuildOutputs(dsh)
  run(process.execPath, ['--import', 'tsx/esm', 'scripts/build.ts', '--profile', 'official'], dsh)
}

const packs = {}
for (const family of ['dsh', 'vendor']) {
  const directory = join(dsh, PACK_ROOT, family)
  // pack.ts clears its own output directory, so the two families must not share one
  rmSync(directory, { recursive: true, force: true })
  run(
    process.execPath,
    ['--import', 'tsx/esm', 'scripts/release/pack.ts', '--family', family, '--out', join(PACK_ROOT, family), '--concurrency', '8'],
    dsh,
  )
  packs[family] = packedDependencies(directory)
}

const packed = new Map([...packs.vendor, ...packs.dsh])
rmSync(out, { recursive: true, force: true })
mkdirSync(out, { recursive: true })
writeFileSync(join(out, 'package.json'), `${JSON.stringify({
  name: 'littlewhale-host',
  version: '0.0.0',
  private: true,
  dependencies: Object.fromEntries([...packed].map(([name, entry]) => [name, entry.url])),
}, null, 2)}\n`)

// Optional dependencies stay out for the same reason the release job omits them: the Landlock
// platform packages need one native build per architecture, and a consumer that cannot install
// them has to start anyway
run('npm', ['install', '--no-audit', '--no-fund', '--package-lock=false', '--omit=optional'], out)

// LittleWhale's own host plugin is copied in rather than packed: it is a few hundred lines of
// plain ESM with no build step, and it has to sit under node_modules so that its import of
// @deepseek-ai/dsh-tools resolves to the same copy the host itself uses
const plugin = fileURLToPath(new URL('../host-plugin', import.meta.url))
const installed = join(out, 'node_modules', 'littlewhale-channel')
cpSync(plugin, installed, { recursive: true })
console.log(`pack-host: installed the LittleWhale plugin from ${plugin}`)

// sharp has no binding this device can load - libvips is built for glibc, and --omit=optional
// dropped even the platform package - so the tree gets a stand-in that answers the one consumer's
// two questions from the PNG header instead. Its own file says what it does and does not do
const imageBackend = fileURLToPath(new URL('../image-backend/sharp', import.meta.url))
const sharpened = join(out, 'node_modules', 'sharp')
rmSync(sharpened, { recursive: true, force: true })
cpSync(imageBackend, sharpened, { recursive: true })
console.log(`pack-host: installed the image backend from ${imageBackend}`)

const { files, bytes } = measure(join(out, 'node_modules'))
console.log(`pack-host: ${String(packed.size)} tarball(s), ${String(files)} file(s), ${(bytes / 1024 / 1024).toFixed(1)} MB in ${out}`)
