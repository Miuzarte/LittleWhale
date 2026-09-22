/**
 * Archive the packed host tree for the APK, plus the stamp the app compares before extracting
 *
 * The tree is ~300 MB in 30k files, and the APK carries it as one zip: copying that many assets
 * through AssetManager one at a time is far slower than streaming a single archive, and one
 * archive keeps the packaged layout identical to the tree `tools/pack-host.mjs` leaves behind
 *
 * The stamp's version hashes every file's path, size and content, so an APK rebuilt from an
 * unchanged tree keeps the same version and the app does not unpack 300 MB again for nothing
 *
 * usage: node tools/zip-host.mjs --tree <dir> --out <assetDir> [--dsh <checkout>]
 */

import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'

/** The archive the app streams out of its own APK */
const ARCHIVE = 'host.zip'

/** The stamp the app reads before deciding to extract, also written into the extracted tree */
const STAMP = 'host-version.txt'

/** Entry name the app needs before the tree counts as usable */
const ENTRY = 'node_modules/@deepseek-ai/dsh/lib/bin.js'

/**
 * bsdtar writes zips; GNU tar cannot, and on Windows the only tar is libarchive's
 * @returns the tar executable to run.
 */
function bsdtar() {
  const system = process.env.SystemRoot === undefined
    ? undefined
    : join(process.env.SystemRoot, 'System32', 'tar.exe')
  return system !== undefined && existsSync(system) ? system : 'tar'
}

/** Every regular file under a root, as tree-relative paths */
function files(root) {
  const found = []
  const walk = (directory, prefix) => {
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) => left.name.localeCompare(right.name))) {
      const path = join(directory, entry.name)
      const name = prefix === '' ? entry.name : `${prefix}/${entry.name}`
      if (entry.isDirectory()) walk(path, name)
      else if (entry.isFile()) found.push({ path, name, size: statSync(path).size })
    }
  }
  walk(root, '')
  return found
}

const { values } = parseArgs({
  options: {
    tree: { type: 'string' },
    out: { type: 'string' },
    dsh: { type: 'string' },
  },
  allowPositionals: false,
})
if (values.tree === undefined || values.out === undefined) {
  throw new Error('usage: node tools/zip-host.mjs --tree <dir> --out <assetDir> [--dsh <checkout>]')
}

const tree = resolve(values.tree)
if (!existsSync(join(tree, ENTRY))) throw new Error(`${tree} is missing ${ENTRY}`)
const out = resolve(values.out)
const archive = join(out, ARCHIVE)

// The version covers content, not timestamps: a repack that lands the same bytes must not make
// every installed app unpack the whole tree again
const digest = createHash('sha256')
const entries = files(tree)
let bytes = 0
for (const file of entries) {
  bytes += file.size
  digest.update(file.name).update('\0').update(String(file.size)).update('\0').update(readFileSync(file.path))
}
const version = digest.digest('hex').slice(0, 32)

mkdirSync(out, { recursive: true })
rmSync(archive, { force: true })
// `-C <tree> .` records every path with a leading `./`, which the app strips while reading
execFileSync(bsdtar(), ['-a', '-c', '-f', archive, '-C', tree, '.'], { stdio: 'inherit' })

let pin = 'unknown'
if (values.dsh !== undefined) {
  try {
    pin = execFileSync('git', ['-C', resolve(values.dsh), 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
  } catch {
    pin = 'unknown'
  }
}
writeFileSync(join(out, STAMP), `version=${version}\nentries=${String(entries.length)}\ndsh=${pin}\n`)

const archiveBytes = statSync(archive).size
console.log(
  `zip-host: ${String(entries.length)} file(s), ${(bytes / 1024 / 1024).toFixed(1)} MB -> `
  + `${ARCHIVE} ${(archiveBytes / 1024 / 1024).toFixed(1)} MB, version ${version}`,
)
