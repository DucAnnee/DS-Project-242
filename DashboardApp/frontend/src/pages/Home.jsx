import { Grid, Box, Paper, Typography } from "@mui/material";

export default function Home() {
  return (
    <Box
      sx={{
        alignSelf: "center",
        alignContent: "center",
        width: "100%",
        height: "100%",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
      }}>
      <Paper
        elevation={8}
        sx={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "row",
          justifyContent: "center",
          alignItems: "center",
        }}>
        <Grid
          container
          spacing={2}
          sx={{ width: "100%", height: "100%", p: 2 }}>
          <Grid size={{ xs: 12, md: 9 }} item>
            <Box
              sx={{
                height: "100%",
                width: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "space-between",
              }}>
              <Box
                sx={{
                  height: "10%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}>
                <SmallInfoWidget
                  title="Total Power Consumption"
                  sub="In the last 24 hours"
                  value={12345}
                />
                <SmallInfoWidget
                  title="Peak Load"
                  sub="In the last 24 hours"
                  value={12456}
                />
                <SmallInfoWidget
                  title="Most consuming area"
                  sub=""
                  value={"District 7"}
                />
              </Box>
              <Box
                sx={{
                  height: "40%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}>
                <MediumChart />
                <LargeChart />
              </Box>
              <Box
                sx={{
                  height: "40%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}>
                <MediumChart />
                <LargeChart />
              </Box>
            </Box>
          </Grid>
          <Grid size={{ xs: 12, md: 3 }} item>
            <Paper
              sx={{
                height: "100%",
                width: "100%",
                display: "flex",
              }}>
              Power consumption each area
            </Paper>
          </Grid>
        </Grid>
      </Paper>
    </Box>
  );
}

function SmallInfoWidget({ title, sub, value }) {
  return (
    <Paper
      sx={{
        height: "100%",
        width: "30%",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        p: 1,
      }}>
      <Box
        sx={{
          height: "100%",
          width: "70%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "flex-start",
        }}>
        <Typography fontSize="0.85rem" fontWeight={700} color="primary.dark">
          {title}
        </Typography>
        <Typography fontSize="0.7rem" fontWeight={400} color="primary.dark">
          {sub}
        </Typography>
      </Box>
      <Box
        sx={{
          height: "100%",
          width: "30%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
        }}>
        <Typography color=".">{value}</Typography>
      </Box>
    </Paper>
  );
}

function MediumChart() {
  return (
    <Paper
      sx={{
        height: "100%",
        width: "38%",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
      }}>
      <Typography>Chart</Typography>
    </Paper>
  );
}

function LargeChart() {
  return (
    <Paper
      sx={{
        height: "100%",
        width: "60%",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
      }}>
      <Typography>Chart</Typography>
    </Paper>
  );
}
