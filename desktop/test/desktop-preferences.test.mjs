import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { DesktopPreferencesError, DesktopPreferencesStore, validatePreferencesPatch } from '../src/desktop-preferences.mjs'

test('renderer preference patches have a strict field and value allowlist', () => {
  assert.deepEqual({ ...validatePreferencesPatch({ autoDownload: true }) }, { autoDownload: true })
  assert.deepEqual({ ...validatePreferencesPatch({
    compressionMode: 'strong',
    targetBySource: Object.assign(Object.create(null), { pdf: 'pdf-compress', ofd: 'docx' })
  }).targetBySource }, { pdf: 'pdf-compress', ofd: 'docx' })
  assert.throws(() => validatePreferencesPatch({ lastSaveDirectory: '/tmp' }), DesktopPreferencesError)
  assert.throws(() => validatePreferencesPatch({ autoDownload: 'yes' }), DesktopPreferencesError)
  assert.throws(() => validatePreferencesPatch({ compressionMode: 'maximum' }), DesktopPreferencesError)
  assert.throws(() => validatePreferencesPatch({ targetBySource: { PDF: 'docx' } }), DesktopPreferencesError)
  assert.throws(() => validatePreferencesPatch({ targetBySource: { pdf: '../docx' } }), DesktopPreferencesError)
})

test('preferences and the private save directory survive concurrent atomic updates', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-prefs-'))
  const store = new DesktopPreferencesStore({ userDataPath: root })
  const chosen = path.join(root, 'chosen')

  await Promise.all([
    store.updatePreferences({ autoDownload: true, targetBySource: { pdf: 'pdf-split' } }),
    store.setLastSaveDirectory(chosen),
    store.updatePreferences({ compressionMode: 'strong' })
  ])

  assert.deepEqual(await store.getPreferences(), {
    version: 1,
    autoDownload: true,
    compressionMode: 'strong',
    targetBySource: { pdf: 'pdf-split' }
  })
  assert.equal(await store.getLastSaveDirectory(), chosen)
  const stored = JSON.parse(await readFile(path.join(root, 'desktop-preferences.json'), 'utf8'))
  assert.equal(stored.lastSaveDirectory, chosen)
  assert.deepEqual((await readdir(root)).sort(), ['desktop-preferences.json'])
})

test('corrupt, oversized, and wrong-version preference files safely use defaults', async () => {
  for (const [name, contents] of [
    ['corrupt', '{broken'],
    ['oversized', 'x'.repeat(65 * 1024)],
    ['future', JSON.stringify({ version: 999, autoDownload: true, lastSaveDirectory: '/secret' })]
  ]) {
    const root = await mkdtemp(path.join(os.tmpdir(), `fuyue-desktop-prefs-${name}-`))
    await writeFile(path.join(root, 'desktop-preferences.json'), contents)
    const store = new DesktopPreferencesStore({ userDataPath: root })
    assert.deepEqual(await store.getPreferences(), {
      version: 1,
      autoDownload: false,
      compressionMode: 'balanced',
      targetBySource: {}
    })
    assert.equal(await store.getLastSaveDirectory(), null)
  }
})

test('renderer reads never disclose the remembered absolute directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-prefs-private-'))
  const store = new DesktopPreferencesStore({ userDataPath: root })
  await store.setLastSaveDirectory(path.join(root, 'private-directory'))
  const exposed = await store.getPreferences()
  assert.equal(Object.hasOwn(exposed, 'lastSaveDirectory'), false)
  assert.equal(JSON.stringify(exposed).includes(root), false)
})

test('the first preference write creates a missing userData directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-prefs-first-run-'))
  const userDataPath = path.join(root, 'not-created-yet', 'user-data')
  const store = new DesktopPreferencesStore({ userDataPath })
  await store.updatePreferences({ autoDownload: true })
  const stored = JSON.parse(await readFile(path.join(userDataPath, 'desktop-preferences.json'), 'utf8'))
  assert.equal(stored.autoDownload, true)
})
