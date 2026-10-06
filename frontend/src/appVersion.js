import manifest from '../package.json' with { type: 'json' }

export const appVersion = manifest.version
