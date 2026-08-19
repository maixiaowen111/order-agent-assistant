import { http } from './http'

export const productPage = (params) => http.get('/api/product/page', { params })
export const productDetail = (id) => http.get(`/api/product/${id}`)

// —— 管理员商品管理（后端 ProductController 校验 ADMIN 角色，见 checkAdmin）——
export const adminProductPage = (params) => http.get('/api/product/admin/page', { params })
export const createProduct = (data) => http.post('/api/product', data)
export const updateProduct = (id, data) => http.put(`/api/product/${id}`, data)
export const updateProductStatus = (id, status) =>
  http.put(`/api/product/${id}/status`, null, { params: { status } })
