import { http } from './http'

export const createOrder = (payload) => http.post('/api/order', payload)
export const myOrders = () => http.get('/api/order')
export const orderDetail = (id) => http.get(`/api/order/${id}`)
export const payOrder = (id) => http.put(`/api/order/${id}/pay`)
export const cancelOrder = (id) => http.put(`/api/order/${id}/cancel`)
