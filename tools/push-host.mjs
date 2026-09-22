/**
 * Push a packed host tree into the debug app's sandbox, for iterating without rebuilding the APK
 *
 * The shipped path is an APK asset the app extracts on first run, which costs a full pack and
 * assemble cycle per change. This puts the same tree in place directly instead: the tarball is
 * read by the shell domain, which can see `/data/local/tmp`, and unpacked by `run-as`, which
 * runs in the app's own domain, so the files land with the app's uid and SELinux label
 *
 * The app unpacks its own copy over a tree whose version stamp does not match the archive's, so
 * the pushed tree is stamped with the version the last build wrote (`:app:zipHostTree`). Without
 * that stamp the app replaces the pushed tree on the next start
 *
 * usage: node tools/push-host.mjs <treeDir> [--package <id>] [--serial <adb serial>] [--stamp <file>]
 */

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { parseArgs } from 'node:util'

/** The adb from the local Android SDK, which is not on PATH */
const ADB = 'B:\\Software\\AndroidSDK\\platform-tools\\adb.exe'

/** Name the app reads the version from, in the assets and in the unpacked tree */
const STAMP = 'host-version.txt'

/** Run adb against the device, returning trimmed stdout */
function adb(args) {
  return execFileSync(ADB, args, { encoding: 'utf8' }).trim()
}

const { values, positionals } = parseArgs({
  options: {
    package: { type: 'string', default: 'io.github.miuzarte.littlewhale' },
    serial: { type: 'string', default: '192.168.1.103:5555' },
    stamp: { type: 'string', default: 'app/build/generated/host-assets/host-version.txt' },
  },
  allowPositionals: true,
})
const [treeArgument] = positionals
if (treeArgument === undefined) throw new Error('usage: node tools/push-host.mjs <treeDir> [--package <id>]')

const tree = resolve(treeArgument)
if (!existsSync(join(tree, 'node_modules'))) throw new Error(`${tree} has no node_modules`)

// Only a build-written version is interpolated into a device shell command
const stampFile = resolve(values.stamp)
const version = existsSync(stampFile)
  ? /^version=([0-9a-f]{32})$/m.exec(readFileSync(stampFile, 'utf8'))?.[1]
  : undefined
if (version === undefined) {
  console.warn(`push-host: no version in ${stampFile}, so the app will unpack its own tree over this one`)
  console.warn('push-host: run `gradlew :app:zipHostTree` first to keep the pushed tree')
}
const stampTree = version === undefined ? '' : ` && printf "%s\\n" ${version} > files/host/${STAMP}`

const archive = join(tmpdir(), 'lw-host.tgz')
const remote = '/data/local/tmp/lw-host.tgz'
rmSync(archive, { force: true })
execFileSync('tar', ['-czf', archive, '-C', tree, '.'], { stdio: 'inherit' })

const withSerial = args => ['-s', values.serial, ...args]
// The device may have dropped the adb connection between runs
execFileSync(ADB, ['connect', values.serial], { stdio: 'inherit' })
adb(withSerial(['shell', `rm -f ${remote}`]))
execFileSync(ADB, withSerial(['push', archive, remote]), { stdio: 'inherit' })

// `run-as` starts in the app's data directory, so `files/host` is the sandbox host root
const unpack = `cat ${remote} | run-as ${values.package} sh -c `
  + `'rm -rf files/host && mkdir -p files/host && tar -xzf - -C files/host${stampTree}'`
execFileSync(ADB, withSerial(['shell', unpack]), { stdio: 'inherit' })
adb(withSerial(['shell', `rm -f ${remote}`]))

const listing = adb(withSerial(['shell', `run-as ${values.package} sh -c 'ls files/host && du -sh files/host'`]))
console.log(`push-host: ${tree} -> ${values.package}:files/host`)
console.log(listing)
