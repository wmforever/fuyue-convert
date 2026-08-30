import assert from 'node:assert/strict'
import test from 'node:test'
import { enrichRuntimeRelease, javaProperty } from '../scripts/lib/runtime-release.mjs'

test('reads exact Java runtime identity from show-settings output', () => {
  const output = `Property settings:\n    java.runtime.version = 17.0.20.1+1\n    java.vendor = Eclipse Adoptium\n    java.version = 17.0.20.1\n`
  assert.equal(javaProperty(output, 'java.version'), '17.0.20.1')
  assert.equal(javaProperty(output, 'java.runtime.version'), '17.0.20.1+1')
  assert.equal(javaProperty(output, 'java.vendor'), 'Eclipse Adoptium')
})

test('records missing jlink vendor and build metadata', () => {
  const actual = enrichRuntimeRelease('JAVA_VERSION="17.0.20.1"\r\nMODULES="java.base"\r\n', {
    javaVersion: '17.0.20.1',
    javaRuntimeVersion: '17.0.20.1+1',
    implementor: 'Eclipse Adoptium'
  })
  assert.equal(actual,
    'JAVA_VERSION="17.0.20.1"\nMODULES="java.base"\nJAVA_RUNTIME_VERSION="17.0.20.1+1"\nIMPLEMENTOR="Eclipse Adoptium"\n')
})

test('rejects release metadata that conflicts with the executable runtime', () => {
  assert.throws(() => enrichRuntimeRelease(
    'JAVA_VERSION="17.0.20.1"\nIMPLEMENTOR="Another Vendor"\n',
    {
      javaVersion: '17.0.20.1',
      javaRuntimeVersion: '17.0.20.1+1',
      implementor: 'Eclipse Adoptium'
    }
  ), /IMPLEMENTOR 与实际运行时不一致/)
})

test('requires the original jlink JAVA_VERSION exactly once', () => {
  const identity = {
    javaVersion: '17.0.20.1',
    javaRuntimeVersion: '17.0.20.1+1',
    implementor: 'Eclipse Adoptium'
  }
  assert.throws(() => enrichRuntimeRelease('MODULES="java.base"\n', identity), /缺少原始 JAVA_VERSION/)
  assert.throws(() => enrichRuntimeRelease(
    'JAVA_VERSION="17.0.20.1"\nJAVA_VERSION="17.0.20.1"\n', identity
  ), /JAVA_VERSION 重复/)
})

test('rejects a runtime build that conflicts with the executable', () => {
  assert.throws(() => enrichRuntimeRelease(
    'JAVA_VERSION="17.0.20.1"\nJAVA_RUNTIME_VERSION="17.0.20.1+2"\n',
    {
      javaVersion: '17.0.20.1',
      javaRuntimeVersion: '17.0.20.1+1',
      implementor: 'Eclipse Adoptium'
    }
  ), /JAVA_RUNTIME_VERSION 与实际运行时不一致/)
})
