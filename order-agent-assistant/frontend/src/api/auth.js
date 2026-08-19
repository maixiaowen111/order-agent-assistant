import { http } from './http'

export const register = (payload) => http.post('/api/user/register', payload)
export const login = (payload) => http.post('/api/user/login', payload)
export const logout = () => http.post('/api/user/logout')
