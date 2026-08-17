import { http } from './http'

export async function getAppSettings() {
  const { data } = await http.get('/api/settings')
  return data
}

export async function updateAppSettings(payload) {
  const { data } = await http.put('/api/settings', payload)
  return data
}

export async function updateEmailSenderSettings(payload) {
  const { data } = await http.put('/api/settings/email-sender', payload)
  return data
}

export async function getImportFieldSettings() {
  const { data } = await http.get('/api/settings/import-fields')
  return data
}

export async function updateImportFieldSettings(payload) {
  const { data } = await http.put('/api/settings/import-fields', payload)
  return data
}

export async function getEmailRecipients() {
  const { data } = await http.get('/api/email-recipients')
  return data
}

export async function createEmailRecipient(payload) {
  const { data } = await http.post('/api/email-recipients', payload)
  return data
}

export async function updateEmailRecipient(id, payload) {
  const { data } = await http.put(`/api/email-recipients/${id}`, payload)
  return data
}

export async function deleteEmailRecipient(id) {
  await http.delete(`/api/email-recipients/${id}`)
}
