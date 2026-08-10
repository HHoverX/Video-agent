export interface AuthUser {
  id: number
  username: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: AuthUser
}

export interface AuthCredentials {
  username: string
  password: string
}
