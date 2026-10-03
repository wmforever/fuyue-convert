import test from 'node:test'
import assert from 'node:assert/strict'
import { acceptsInputFile, imageFileLabel, inputExtensionsForRoute } from '../src/fileSelection.js'

test('either image-to-PDF entry accepts mixed PNG/JPEG inputs in list order', () => {
  const names = ['scan.JPEG', 'cover.png', 'photo.JPG', 'note.pdf', 'fake.png.exe']
  for (const sourceFormat of ['png', 'jpg']) {
    const route = { sourceFormat, targetFormat: 'pdf', inputExtension: `.${sourceFormat}` }
    assert.deepEqual(inputExtensionsForRoute(route), ['.png', '.jpg', '.jpeg'])
    assert.deepEqual(names.filter(name => acceptsInputFile(route, name)), names.slice(0, 3))
  }
  assert.equal(imageFileLabel('scan.JPEG'), 'JPG')
  assert.equal(imageFileLabel('cover.PNG'), 'PNG')
})

test('other conversion routes retain their source-format restriction and aliases', () => {
  const jpgOcr = { sourceFormat: 'jpg', targetFormat: 'txt', inputExtension: '.jpg' }
  assert.equal(acceptsInputFile(jpgOcr, 'scan.jpeg'), true)
  assert.equal(acceptsInputFile(jpgOcr, 'scan.png'), false)
  const pngOcr = { sourceFormat: 'png', targetFormat: 'docx', inputExtension: '.png' }
  assert.equal(acceptsInputFile(pngOcr, 'scan.jpeg'), false)
  assert.equal(acceptsInputFile({ sourceFormat: 'uof', targetFormat: 'docx' }, 'document.UOT'), true)
  assert.equal(acceptsInputFile({ sourceFormat: 'pdf', inputExtension: '.pdf' }, 'document.docx'), false)
  assert.equal(acceptsInputFile(null, 'document.ofd'), true)
  assert.equal(acceptsInputFile(null, null), false)
})
