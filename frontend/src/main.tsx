import React, { type ReactNode } from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, useNavigate } from "react-router-dom";
import { AuthProvider } from "react-oidc-context";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { ApiError } from "./api/client";
import type { SigninState } from "./auth/useSession";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Une erreur 4xx (non trouve, interdit...) ne se corrigera pas en reessayant.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2,
      refetchOnWindowFocus: false,
    },
  },
});

/**
 * FE-2 : Authorization Code + PKCE. react-oidc-context active PKCE d'office pour un
 * client public ; aucun secret n'est embarque dans le navigateur.
 *
 * Le fournisseur est place sous le routeur pour pouvoir, au retour de Keycloak, nettoyer
 * l'URL (?code=...&state=...) et ramener l'utilisateur sur la page d'ou il venait.
 */
function OidcProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();

  return (
    <AuthProvider
      authority={import.meta.env.VITE_KEYCLOAK_AUTHORITY}
      client_id={import.meta.env.VITE_KEYCLOAK_CLIENT_ID}
      redirect_uri={`${window.location.origin}/`}
      onSigninCallback={(user) => {
        const returnTo = (user?.state as SigninState | undefined)?.returnTo;
        navigate(returnTo ?? "/", { replace: true });
      }}
    >
      {children}
    </AuthProvider>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BrowserRouter>
      <OidcProvider>
        <QueryClientProvider client={queryClient}>
          <App />
        </QueryClientProvider>
      </OidcProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
