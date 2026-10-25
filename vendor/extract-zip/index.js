const debug = require('debug')('extract-zip')
// eslint-disable-next-line node/no-unsupported-features/node-builtins
const { promises: fs } = require('fs')
const getStream = require('get-stream')
const path = require('path')
const { randomUUID } = require('crypto')
const { promisify } = require('util')
const stream = require('stream')
const yauzl = require('yauzl')

const openZip = promisify(yauzl.open)
const pipeline = promisify(stream.pipeline)

function confined (root, target) {
  const relative = path.relative(root, target)
  if (path.isAbsolute(relative) || relative === '..' || relative.startsWith('..' + path.sep)) {
    throw new Error(`Out of bound path "${target}"`)
  }
}

async function ensureDirectories (root, target, mode) {
  confined(root, target)
  let current = root
  for (const component of path.relative(root, target).split(path.sep).filter(Boolean)) {
    current = path.join(current, component)
    try {
      await fs.mkdir(current, { mode })
    } catch (error) {
      if (error.code !== 'EEXIST') throw error
    }
    const stat = await fs.lstat(current)
    if (stat.isSymbolicLink() || !stat.isDirectory()) {
      throw new Error(`Unsafe link or non-directory in archive path "${current}"`)
    }
  }
}

async function validateLink (root, dest, link) {
  if (path.isAbsolute(link) || path.win32.isAbsolute(link) || link.includes('\\') || link.includes('\0')) {
    throw new Error(`Unsafe archive link at "${dest}"`)
  }
  let target = path.resolve(path.dirname(dest), link)
  confined(root, target)
  // A dangling link is allowed only when its nearest existing ancestor is
  // also confined. Existing symlink chains must not lead outside the root.
  for (;;) {
    try {
      confined(root, await fs.realpath(target))
      return
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
      const stat = await fs.lstat(target).catch(error => {
        if (error.code !== 'ENOENT') throw error
        return null
      })
      if (stat && stat.isSymbolicLink()) throw new Error(`Unresolved archive link at "${target}"`)
      target = path.dirname(target)
    }
  }
}

class Extractor {
  constructor (zipPath, opts) {
    this.zipPath = zipPath
    this.opts = opts
  }

  async extract () {
    debug('opening', this.zipPath, 'with opts', this.opts)

    this.zipfile = await openZip(this.zipPath, { lazyEntries: true })
    this.canceled = false

    return new Promise((resolve, reject) => {
      this.zipfile.on('error', err => {
        this.canceled = true
        reject(err)
      })
      this.zipfile.readEntry()

      this.zipfile.on('close', () => {
        if (!this.canceled) {
          debug('zip extraction complete')
          resolve()
        }
      })

      this.zipfile.on('entry', async entry => {
        /* istanbul ignore if */
        if (this.canceled) {
          debug('skipping entry', entry.fileName, { cancelled: this.canceled })
          return
        }

        debug('zipfile entry', entry.fileName)

        if (entry.fileName.startsWith('__MACOSX/')) {
          this.zipfile.readEntry()
          return
        }

        const destDir = path.dirname(path.join(this.opts.dir, entry.fileName))

        try {
          await ensureDirectories(this.opts.dir, destDir)

          await this.extractEntry(entry)
          debug('finished processing', entry.fileName)
          this.zipfile.readEntry()
        } catch (err) {
          this.canceled = true
          this.zipfile.close()
          reject(err)
        }
      })
    })
  }

  async extractEntry (entry) {
    /* istanbul ignore if */
    if (this.canceled) {
      debug('skipping entry extraction', entry.fileName, { cancelled: this.canceled })
      return
    }

    if (this.opts.onEntry) {
      this.opts.onEntry(entry, this.zipfile)
    }

    const dest = path.join(this.opts.dir, entry.fileName)

    // convert external file attr int into a fs stat mode int
    const mode = (entry.externalFileAttributes >> 16) & 0xFFFF
    // check if it's a symlink or dir (using stat mode constants)
    const IFMT = 61440
    const IFDIR = 16384
    const IFLNK = 40960
    const symlink = (mode & IFMT) === IFLNK
    let isDir = (mode & IFMT) === IFDIR

    // Failsafe, borrowed from jsZip
    if (!isDir && entry.fileName.endsWith('/')) {
      isDir = true
    }

    // check for windows weird way of specifying a directory
    // https://github.com/maxogden/extract-zip/issues/13#issuecomment-154494566
    const madeBy = entry.versionMadeBy >> 8
    if (!isDir) isDir = (madeBy === 0 && entry.externalFileAttributes === 16)

    debug('extracting entry', { filename: entry.fileName, isDir: isDir, isSymlink: symlink })

    const procMode = this.getExtractedMode(mode, isDir) & 0o777

    // always ensure folders are created
    const destDir = isDir ? dest : path.dirname(dest)

    const mkdirOptions = { recursive: true }
    if (isDir) {
      mkdirOptions.mode = procMode
    }
    debug('mkdir', { dir: destDir, ...mkdirOptions })
    await ensureDirectories(this.opts.dir, destDir, mkdirOptions.mode)
    if (isDir) return

    debug('opening read stream', dest)
    const readStream = await promisify(this.zipfile.openReadStream.bind(this.zipfile))(entry)

    if (symlink) {
      const link = await getStream(readStream, { maxBuffer: 65536 })
      await validateLink(this.opts.dir, dest, link)
      debug('creating symlink', link, dest)
      await fs.symlink(link, dest)
    } else {
      const existing = await fs.lstat(dest).catch(error => {
        if (error.code !== 'ENOENT') throw error
        return null
      })
      if (existing && existing.isSymbolicLink()) {
        readStream.destroy()
        throw new Error(`Refusing to overwrite archive link "${dest}"`)
      }
      // Exclusive temporary creation plus rename never follows a destination
      // symlink or writes through an existing hard link.
      const temporary = path.join(destDir, '.extract-' + randomUUID())
      const handle = await fs.open(temporary, 'wx', procMode)
      try {
        await pipeline(readStream, handle.createWriteStream())
        await fs.rename(temporary, dest)
      } finally {
        await handle.close()
        await fs.unlink(temporary).catch(error => {
          if (error.code !== 'ENOENT') throw error
        })
      }
    }
  }

  getExtractedMode (entryMode, isDir) {
    let mode = entryMode
    // Set defaults, if necessary
    if (mode === 0) {
      if (isDir) {
        if (this.opts.defaultDirMode) {
          mode = parseInt(this.opts.defaultDirMode, 10)
        }

        if (!mode) {
          mode = 0o755
        }
      } else {
        if (this.opts.defaultFileMode) {
          mode = parseInt(this.opts.defaultFileMode, 10)
        }

        if (!mode) {
          mode = 0o644
        }
      }
    }

    return mode
  }
}

module.exports = async function (zipPath, opts) {
  debug('creating target directory', opts.dir)

  if (!path.isAbsolute(opts.dir)) {
    throw new Error('Target directory is expected to be absolute')
  }

  await fs.mkdir(opts.dir, { recursive: true })
  opts.dir = await fs.realpath(opts.dir)
  return new Extractor(zipPath, opts).extract()
}
