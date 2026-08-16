import { WebStorageStateStore } from 'oidc-client-ts'
import type { AuthProviderProps } from 'react-oidc-context'

/**
 * OIDC configuration for the browser client.
 *
 * Authorization Code + PKCE against the customer's identity provider. ArchGrid never
 * sees a password and never issues a token of its own — the API is a resource server
 * that only validates what the IdP signed.
 *
 * Every value is read from the environment so that an on-premise install can be pointed
 * at its own IdP by configuring the container, without rebuilding the bundle.
 */
export const oidcConfig: AuthProviderProps = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/realms/archgrid',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'archgrid-web',
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid profile email',
  // Session storage rather than local storage: a token that outlives the browser tab is
  // a finding waiting to happen in a customer security review.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: true,
  // Strip ?code=… from the address bar after the redirect completes.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname)
  },
}
