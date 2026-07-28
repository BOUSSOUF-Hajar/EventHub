import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "react-oidc-context";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";

const oidcConfig = {
  authority: import.meta.env.VITE_KEYCLOAK_AUTHORITY,
  client_id: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
  redirect_uri: window.location.origin,
  // PKCE est active par defaut par react-oidc-context pour un client public
};

const queryClient = new QueryClient();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AuthProvider {...oidcConfig}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </AuthProvider>
  </React.StrictMode>
);
