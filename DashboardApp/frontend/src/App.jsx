import { Box, Grid } from "@mui/material";
import { Outlet } from "react-router-dom";
import Sidebar from "./components/Sidebar";

function App() {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        minHeight: "100vh",
        minWidth: "100vw",
      }}>
      <Grid container spacing={0} columns={{ xs: 24 }}>
        <Grid size={{ xs: 3 }} item>
          <Box
            sx={{
              alignContent: "center",
              display: "flex",
              flexDirection: "column",
              justifyContent: "center",
              alignItems: "center",
              height: "100vh",
            }}>
            <Sidebar />
          </Box>
        </Grid>
        <Grid size={{ xs: 21 }} item>
          <Outlet />
        </Grid>
      </Grid>
    </div>
  );
}

export default App;
