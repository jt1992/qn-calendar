import { http } from './http'

export async function getAppSettings() {
  const { data } = await http.get('/api/settings')
  return data
}

export async function updateAppSettings(payload) {
  const { data } = await http.put('/api/settings', payload)
  return data
}
