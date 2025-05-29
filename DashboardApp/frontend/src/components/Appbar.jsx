import { Box, Paper, Typography, Button } from "@mui/material";
import { useState, useEffect } from "react";

export default function Home() {
  const [currentTime, setCurrentTime] = useState(new Date().toLocaleString());
  const [totalConsumption, setTotalConsumption] = useState(12345);
  const [peakLoad, setPeakLoad] = useState(678);
  const [alerts, setAlerts] = useState(0);
  const [currentDistrict, setCurrentDistrict] = useState("Tan Phu");

  useEffect(() => {
    const interval = setInterval(() => {
      setCurrentTime(new Date().toLocaleString());
    }, 1000);

    return () => clearInterval(interval);
  }, []);

  return (
    <Box
      sx={{
        width: "100%",
        display: "flex",
        justifyContent: "flex-start",
        alignItems: "center",
        flexDirection: "column",
      }}
    >
      <Paper
        elevation={8}
        sx={{
          width: "100%",
          display: "flex",
          flexDirection: "row",
          justifyContent: "space-between",
          alignItems: "center",
          backgroundColor: "#2e3b4e",
        }}
      >
        <Box
          sx={{
            display: "flex",
            width: "100%",
            flexDirection: "row",
            justifyContent: "space-between",
            alignItems: "center",
            p: 2,
          }}
        >
          <Typography variant="h6" sx={{ color: "white" }}>
            Real-time Energy Monitoring
          </Typography>
          <Typography variant="body2" sx={{ color: "lightgray" }}>
            {currentTime}
          </Typography>
        </Box>
      </Paper>
    </Box>
  );
}
