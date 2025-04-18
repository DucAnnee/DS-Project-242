import { HomeFilled, ReportProblem } from "@mui/icons-material";
import { Box, Typography } from "@mui/material";

export default function Sidebar() {
  return (
    <Box
      sx={{
        width: "100%",
        height: "100%",
        backgroundColor: "primary.dark",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        boxShadow: "0px -10px 30px rgba(0, 0, 0, 0.35)",
      }}>
      {/* Bottom Text */}
      <Typography
        variant="body"
        color="white"
        fontWeight={700}
        sx={{ textAlign: "center", marginTop: "1rem" }}>
        METER DASHBOARD
      </Typography>
      <Box
        sx={{
          py: 12,
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          alignSelf: "center",
          alignItems: "center",
          justifySelf: "center",
          justifyContent: "flex-start",
        }}>
        <SidebarNavButton
          icon={<HomeFilled />}
          text="Home"
          selected={true}
          onClick={() => {
            console.log("Home clicked");
          }}
        />
        <SidebarNavButton
          icon={<ReportProblem />}
          text="Alerts"
          selected={false}
          onClick={() => {
            console.log("Alert clicked");
          }}
        />
      </Box>
    </Box>
  );
}

function SidebarNavButton({ icon, text, selected, onClick }) {
  return (
    <Box
      sx={{
        width: "90%",
        display: "flex",
        alignItems: "center",
        justifyContent: "flex-start",
        backgroundColor: selected ? "primary.main" : "transparent",
        padding: "1rem",
        cursor: "pointer",
      }}
      onClick={onClick}>
      {icon}
      <Typography
        variant="body1"
        color={selected ? "white" : "primary.main"}
        fontWeight={700}
        sx={{ marginLeft: "0.5rem" }}>
        {text}
      </Typography>
    </Box>
  );
}
