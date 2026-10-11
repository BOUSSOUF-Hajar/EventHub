import { useCallback, useMemo } from "react";
import { useAuth } from "react-oidc-context";
import { rolesFromAccessToken } from "../lib/token";

export interface SigninState {
  returnTo?: string;
}

/**
 * Vue simplifiee de la session OIDC pour les composants : qui est connecte, avec quels
 * roles, et comment se connecter / se deconnecter.
 */
export function useSession() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const roles = useMemo(() => rolesFromAccessToken(token), [token]);

  /** Renvoie vers Keycloak (Authorization Code + PKCE), puis revient sur la page d'origine. */
  const login = useCallback(
    (returnTo?: string) => {
      const state: SigninState = { returnTo: returnTo ?? window.location.pathname };
      void auth.signinRedirect({ state });
    },
    [auth],
  );

  /**
   * Deconnexion complete : on ferme aussi la session Keycloak. Se contenter d'oublier le
   * jeton cote navigateur reconnecterait aussitot le meme utilisateur, sans mot de passe.
   */
  const logout = useCallback(() => {
    void auth.signoutRedirect({ post_logout_redirect_uri: `${window.location.origin}/` });
  }, [auth]);

  const isAdmin = roles.includes("ADMIN");

  return {
    isLoading: auth.isLoading,
    isAuthenticated: auth.isAuthenticated,
    token,
    username: auth.user?.profile.preferred_username ?? auth.user?.profile.email ?? "",
    email: auth.user?.profile.email ?? "",
    roles,
    canBook: isAdmin || roles.includes("CUSTOMER"),
    canManageEvents: isAdmin || roles.includes("ORGANIZER"),
    login,
    logout,
  };
}
