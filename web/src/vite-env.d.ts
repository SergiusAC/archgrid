/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Issuer URL of the identity provider, e.g. https://idp.customer.example/realms/archgrid */
  readonly VITE_OIDC_AUTHORITY?: string
  /** Public client id registered with that identity provider. */
  readonly VITE_OIDC_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
