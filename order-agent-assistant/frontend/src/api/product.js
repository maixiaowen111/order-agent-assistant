import { http } from './http'

export const productPage = (params) => http.get('/api/product/page', { params })
export const productDetail = (id) => http.get(`/api/product/${id}`)

// —— 管理员商品管理（后端 ProductController 校验 ADMIN 角色，见 checkAdmin）——
export const adminProductPage = (params) => http.get('/api/product/admin/page', { params })
export const createProduct = (data) => http.post('/api/product', data)
export const updateProduct = (id, data) => http.put(`/api/product/${id}`, data)
export const updateProductStatus = (id, status) =>
  http.put(`/api/product/${id}/status`, null, { params: { status } })

// 上传商品图片，返回相对 URL（/uploads/xxx.jpg），保存商品时写入 image 字段
export const uploadImage = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/api/product/image', fd) // axios 自动带 multipart boundary，勿手动 set Content-Type
}

// 删除已上传但未保存的图片（清理孤儿文件）；已被商品引用的图后端会跳过，不误删
export const deleteUploadedImage = (url) => http.delete('/api/product/image', { params: { url } })
