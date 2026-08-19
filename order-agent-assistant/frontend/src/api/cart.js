import { http } from './http'

export const addCart = (payload) => http.post('/api/cart', payload)
export const myCart = () => http.get('/api/cart')
// 改量只发 {quantity}，别带 productId（后端 AddCartDTO.productId 无校验但语义不对）
export const updateQty = (cartId, quantity) => http.put(`/api/cart/${cartId}`, { quantity })
export const removeCart = (cartId) => http.delete(`/api/cart/${cartId}`)
export const clearCart = () => http.delete('/api/cart/clear')
