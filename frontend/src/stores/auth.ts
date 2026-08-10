import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import type { AuthUser, LoginResponse } from '@/types/auth'

const TOKEN_KEY = 'videoagent:auth:token'
const USER_KEY = 'videoagent:auth:user'

function initialToken() {
  try {
    return window.localStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

function initialUser(): AuthUser | null {
  try {
    const value = window.localStorage.getItem(USER_KEY)
    return value ? JSON.parse(value) as AuthUser : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(initialToken())
  const user = ref<AuthUser | null>(initialUser())
  const isAuthenticated = computed(() => Boolean(token.value && user.value))

  function setSession(session: LoginResponse) {
    token.value = session.token
    user.value = session.user
    window.localStorage.setItem(TOKEN_KEY, session.token)
    window.localStorage.setItem(USER_KEY, JSON.stringify(session.user))
  }

  function clearSession() {
    token.value = ''
    user.value = null
    try {
      window.localStorage.removeItem(TOKEN_KEY)
      window.localStorage.removeItem(USER_KEY)
    } catch {
      // In-memory state is still cleared when storage is unavailable.
    }
  }

  return { token, user, isAuthenticated, setSession, clearSession }
})
