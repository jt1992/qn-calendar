import { defineStore } from 'pinia'
import { getAppSettings, updateAppSettings } from '../api/settings'

let errorTimer = null

export const useAppSettingsStore = defineStore('appSettings', {
  state: () => ({
    settings: {
      estimatedHourlyBaseAmount: 100
    },
    loading: false,
    saving: false,
    error: ''
  }),

  actions: {
    async fetchSettings() {
      this.loading = true
      this.clearError()

      try {
        this.settings = await getAppSettings()
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.loading = false
      }
    },

    async saveSettings(settings) {
      this.saving = true
      this.clearError()

      try {
        this.settings = await updateAppSettings(settings)
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.saving = false
      }
    },

    setError(message) {
      this.error = message

      if (errorTimer) {
        window.clearTimeout(errorTimer)
      }

      errorTimer = window.setTimeout(() => {
        this.error = ''
        errorTimer = null
      }, 5000)
    },

    clearError() {
      this.error = ''

      if (errorTimer) {
        window.clearTimeout(errorTimer)
        errorTimer = null
      }
    }
  }
})
