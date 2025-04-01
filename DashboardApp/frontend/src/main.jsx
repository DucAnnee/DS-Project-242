import {
  createTheme,
  StyledEngineProvider,
  ThemeProvider,
} from "@mui/material";
import { RouterProvider } from "react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import "@fontsource/be-vietnam-pro/300.css";
import "@fontsource/be-vietnam-pro/400.css";
import "@fontsource/be-vietnam-pro/500.css";
import "@fontsource/be-vietnam-pro/700.css";

import router from "./routers/router.jsx";
import { AuthProvider } from "./contexts/AuthContext.jsx";

const theme = createTheme({
  palette: {
    bg: {
      main: "#f7fff7",
    },
    primary: {
      main: "#4ecdc4",
      dark: "#1a535c",
    },
    alert: {
      main: "#ff6b6b",
      contrast: "#ffe66d",
    },
  },
  typography: {
    fontFamily: "Be Vietnam Pro",
  },
});
createRoot(document.getElementById("root")).render(
  <StrictMode>
    <AuthProvider>
      <StyledEngineProvider injectFirst>
        <ThemeProvider theme={theme}>
          <RouterProvider router={router} />
        </ThemeProvider>
      </StyledEngineProvider>
    </AuthProvider>
  </StrictMode>,
);
