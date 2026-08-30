let pdfJsModulePromise

export function loadPdfJs() {
  if (!pdfJsModulePromise) {
    pdfJsModulePromise = Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url')
    ]).then(([pdfJs, worker]) => {
      pdfJs.GlobalWorkerOptions.workerSrc = worker.default
      return pdfJs
    }).catch(error => {
      pdfJsModulePromise = null
      throw error
    })
  }
  return pdfJsModulePromise
}

export function pdfPreviewError(error) {
  if (error?.name === 'PasswordException') return '受密码保护的 PDF 无法本地预览，请解除密码后重新选择'
  if (error?.name === 'InvalidPDFException') return '文件不是有效的 PDF，无法预览，请更换文件'
  if (error?.name === 'MissingPDFException') return '无法读取所选 PDF，请重新选择文件'
  return '预览生成失败，可重新选择文件或提交后查看转换服务诊断'
}

export function blocksPdfSubmission(error) {
  return ['PasswordException', 'InvalidPDFException', 'MissingPDFException'].includes(error?.name)
}
