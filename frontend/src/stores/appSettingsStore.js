import { defineStore } from 'pinia'
import { getAppSettings, updateAppSettings } from '../api/settings'

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
      this.error = ''

      try {
        this.settings = await getAppSettings()
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async saveSettings(settings) {
      this.saving = true
      this.error = ''

      try {
        this.settings = await updateAppSettings(settings)
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.saving = false
      }
    }
  }
})
