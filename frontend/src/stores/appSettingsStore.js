import { defineStore } from 'pinia'
import {
  createEmailRecipient as createEmailRecipientRequest,
  deleteEmailRecipient as deleteEmailRecipientRequest,
  getAppSettings,
  getEmailRecipients,
  updateAppSettings,
  updateEmailRecipient as updateEmailRecipientRequest,
  updateEmailSenderSettings
} from '../api/settings'

let errorTimer = null

export const useAppSettingsStore = defineStore('appSettings', {
  state: () => ({
    settings: {
      estimatedHourlyBaseAmount: 100,
      weekViewDefaultStartTime: '06:00',
      emailSender: {
        configured: false,
        senderEmailMasked: '',
        senderEmail: '',
        smtpHost: '',
        smtpPort: 465,
        smtpSecurity: 'SSL'
      }
    },
    emailRecipients: [],
    settingsLoaded: false,
    loading: false,
    saving: false,
    recipientsLoading: false,
    recipientSaving: false,
    error: ''
  }),

  actions: {
    async fetchSettings() {
      this.loading = true
      this.clearError()

      try {
        this.settings = await getAppSettings()
        this.settingsLoaded = true
        return this.settings
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.loading = false
      }
    },

    async ensureSettingsLoaded() {
      if (this.settingsLoaded) {
        return this.settings
      }

      return this.fetchSettings()
    },

    async saveSettings(settings) {
      this.saving = true
      this.clearError()

      try {
        this.settings = await updateAppSettings(settings)
        this.settingsLoaded = true
        return this.settings
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.saving = false
      }
    },

    async saveEmailSenderSettings(settings) {
      this.saving = true
      this.clearError()

      try {
        this.settings = await updateEmailSenderSettings(settings)
        this.settingsLoaded = true
        return this.settings
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.saving = false
      }
    },

    async fetchEmailRecipients() {
      this.recipientsLoading = true
      this.clearError()

      try {
        this.emailRecipients = await getEmailRecipients()
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.recipientsLoading = false
      }
    },

    async createEmailRecipient(recipient) {
      this.recipientSaving = true
      this.clearError()

      try {
        const created = await createEmailRecipientRequest(recipient)
        this.emailRecipients = sortEmailRecipients([...this.emailRecipients, created])
        return created
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.recipientSaving = false
      }
    },

    async updateEmailRecipient(id, recipient) {
      this.recipientSaving = true
      this.clearError()

      try {
        const updated = await updateEmailRecipientRequest(id, recipient)
        this.emailRecipients = sortEmailRecipients(
          this.emailRecipients.map((current) => current.id === updated.id ? updated : current)
        )
        return updated
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.recipientSaving = false
      }
    },

    async deleteEmailRecipient(id) {
      this.recipientSaving = true
      this.clearError()

      try {
        await deleteEmailRecipientRequest(id)
        this.emailRecipients = this.emailRecipients.filter((recipient) => recipient.id !== id)
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.recipientSaving = false
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

function sortEmailRecipients(recipients) {
  return [...recipients].sort((left, right) => {
    const lastUsedComparison = compareLastUsed(right.lastUsedAt, left.lastUsedAt)

    if (lastUsedComparison !== 0) {
      return lastUsedComparison
    }

    if (left.usageCount !== right.usageCount) {
      return right.usageCount - left.usageCount
    }

    return (left.name || left.email).localeCompare(right.name || right.email, 'zh-CN')
  })
}

function compareLastUsed(left, right) {
  if (!left && !right) {
    return 0
  }

  if (!left) {
    return -1
  }

  if (!right) {
    return 1
  }

  return new Date(left).getTime() - new Date(right).getTime()
}
