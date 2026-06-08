import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 20000
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      '請求失敗'

    return Promise.reject({
      message,
      status: error.response?.status,
      details: error.response?.data?.details || []
    })
  }
)
