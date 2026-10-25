const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { crc32 } = require('node:zlib')
const { test } = require('node:test')
const extract = require('.')

function zip(entries) {
  const local = [], central = []
  let offset = 0
  for (const [name, text, mode = 0o100644] of entries) {
    const filename = Buffer.from(name), data = Buffer.from(text)
    const header = Buffer.alloc(30), directory = Buffer.alloc(46)
    header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4)
    header.writeUInt32LE(crc32(data), 14)
    header.writeUInt32LE(data.length, 18); header.writeUInt32LE(data.length, 22)
    header.writeUInt16LE(filename.length, 26)
    directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(0x0314, 4)
    header.copy(directory, 6, 4, 28)
    directory.writeUInt32LE((mode * 65536) >>> 0, 38)
    directory.writeUInt32LE(offset, 42)
    local.push(header, filename, data); central.push(directory, filename)
    offset += header.length + filename.length + data.length
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, directory, end])
}

test('rejects escaping links and link overwrite without touching outside files', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-zip-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const outside = path.join(root, 'outside.txt')
  await fs.writeFile(outside, 'unchanged')
  for (const [name, entries] of [
    ['relative', [['link', '../outside.txt', 0o120777], ['link', 'overwrite']]],
    ['absolute', [['link', outside, 0o120777]]],
    ['link-parent', [['dir', '..', 0o120777], ['dir/new-directory/file', 'escape']]],
  ]) {
    const archive = path.join(root, name + '.zip'), output = path.join(root, name)
    await fs.writeFile(archive, zip(entries))
    await assert.rejects(extract(archive, { dir: output }), /link|bound|exist/i)
    assert.equal(await fs.readFile(outside, 'utf8'), 'unchanged')
    await assert.rejects(fs.stat(path.join(root, 'new-directory')), { code: 'ENOENT' })
  }
  const output = path.join(root, 'existing'), archive = path.join(root, 'existing.zip')
  await fs.mkdir(output)
  await fs.symlink(outside, path.join(output, 'file'))
  await fs.writeFile(archive, zip([['file', 'overwrite']]))
  await assert.rejects(extract(archive, { dir: output }), /link|bound|exist/i)
  assert.equal(await fs.readFile(outside, 'utf8'), 'unchanged')
})

test('preserves ordinary files, modes, callbacks, and confined relative links', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-zip-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const archive = path.join(root, 'valid.zip'), output = path.join(root, 'output')
  await fs.writeFile(archive, zip([
    ['bin/run', 'program', 0o100755], ['bin/alias', 'run', 0o120777],
    ['lib/alias', '../bin/run', 0o120777],
  ]))
  const seen = []
  await extract(archive, { dir: output, onEntry: entry => seen.push(entry.fileName) })
  assert.deepEqual(seen, ['bin/run', 'bin/alias', 'lib/alias'])
  assert.equal(await fs.readFile(path.join(output, 'bin/run'), 'utf8'), 'program')
  assert.equal(await fs.readFile(path.join(output, 'lib/alias'), 'utf8'), 'program')
  if (process.platform !== 'win32') assert.equal((await fs.stat(path.join(output, 'bin/run'))).mode & 0o777, 0o755)
})
