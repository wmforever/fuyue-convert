export function isImageToPdfRoute(route) {
  return ['png', 'jpg'].includes(route?.sourceFormat) && route?.targetFormat === 'pdf'
}

export function inputExtensionsForRoute(route) {
  if (isImageToPdfRoute(route)) return ['.png', '.jpg', '.jpeg']
  if (route?.sourceFormat === 'jpg') return ['.jpg', '.jpeg']
  if (route?.sourceFormat === 'uof') return ['.uof', '.uot']
  return [route?.inputExtension || '.ofd']
}

export function acceptsInputFile(route, name) {
  if (typeof name !== 'string') return false
  return inputExtensionsForRoute(route).some(extension => name.toLowerCase().endsWith(extension.toLowerCase()))
}

export function imageFileLabel(name) {
  return /\.png$/i.test(name) ? 'PNG' : 'JPG'
}
