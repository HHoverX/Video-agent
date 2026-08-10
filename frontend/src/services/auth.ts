import { api } from './api'
import type { AuthCredentials, AuthUser, LoginResponse } from '@/types/auth'

export async function register(credentials: AuthCredentials): Promise<AuthUser> {
  const { data } = await api.post<AuthUser>('/auth/register', credentials)
  return data
}

export async function login(credentials: AuthCredentials): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login', credentials)
  return data
}

export async function getCurrentUser(): Promise<AuthUser> {
  const { data } = await api.get<AuthUser>('/auth/me')
  return data
}
