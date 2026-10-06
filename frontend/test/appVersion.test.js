import test from 'node:test'
import assert from 'node:assert/strict'
import manifest from '../package.json' with { type: 'json' }
import { appVersion } from '../src/appVersion.js'

test('the frontend fallback version follows its release manifest', () => {
  assert.equal(appVersion, manifest.version)
  assert.match(appVersion, /^\d+\.\d+\.\d+/)
})
