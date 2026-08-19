import { http } from './http'

export const myNotifications = () => http.get('/api/notification/my')
export const markRead = (id) => http.post(`/api/notification/${id}/read`)
export const markAllRead = () => http.post('/api/notification/read-all')
export const unreadCount = () => http.get('/api/notification/unread-count')
