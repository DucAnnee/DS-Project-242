import React, { useEffect, useState } from "react";
import {
  Grid,
  Box,
  Paper,
  Typography,
  Button,
  Select,
  InputLabel,
  FormControl,
  MenuItem,
} from "@mui/material";
import {
  PieChart as RechartsPieChart,
  Pie,
  Cell,
  Tooltip as RechartsTooltip,
  Legend,
  LineChart as RechartsLineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  ResponsiveContainer,
  Label,
} from "recharts";
import io from "socket.io-client";

// Color palette for Pie charts
const COLORS = ["#0088FE", "#00C49F", "#FFBB28", "#FF8042"];

const sample_data = [
  { timestamp: "13:31", value: 523.12 },
  { timestamp: "13:32", value: 612.45 },
  { timestamp: "13:33", value: 487.33 },
  { timestamp: "13:34", value: 558.29 },
  { timestamp: "13:35", value: 601.17 },
  { timestamp: "13:36", value: 575.86 },
  { timestamp: "13:37", value: 632.9 },
  { timestamp: "13:38", value: 610.44 },
  { timestamp: "13:39", value: 589.22 },
  { timestamp: "13:40", value: 621.55 },
  { timestamp: "13:41", value: 649.11 },
  { timestamp: "13:42", value: 680.37 },
  { timestamp: "13:43", value: 657.24 },
  { timestamp: "13:44", value: 613.98 },
  { timestamp: "13:45", value: 578.46 },
  { timestamp: "13:46", value: 602.89 },
  { timestamp: "13:47", value: 634.52 },
  { timestamp: "13:48", value: 659.17 },
  { timestamp: "13:49", value: 681.03 },
  { timestamp: "13:50", value: 702.44 },
  { timestamp: "13:51", value: 725.31 },
  { timestamp: "13:52", value: 748.29 },
  { timestamp: "13:53", value: 719.88 },
  { timestamp: "13:54", value: 682.75 },
  { timestamp: "13:55", value: 645.22 },
  { timestamp: "13:56", value: 668.39 },
  { timestamp: "13:57", value: 691.14 },
  { timestamp: "13:58", value: 713.87 },
  { timestamp: "13:59", value: 736.25 },
  { timestamp: "14:00", value: 759.12 },
  { timestamp: "14:01", value: 781.44 },
  { timestamp: "14:02", value: 763.55 },
  { timestamp: "14:03", value: 744.33 },
  { timestamp: "14:04", value: 726.19 },
  { timestamp: "14:05", value: 708.02 },
  { timestamp: "14:06", value: 691.88 },
  { timestamp: "14:07", value: 675.44 },
  { timestamp: "14:08", value: 659.77 },
  { timestamp: "14:09", value: 644.22 },
  { timestamp: "14:10", value: 628.91 },
  { timestamp: "14:11", value: 613.58 },
  { timestamp: "14:12", value: 598.4 },
  { timestamp: "14:13", value: 583.27 },
  { timestamp: "14:14", value: 568.19 },
  { timestamp: "14:15", value: 553.03 },
  { timestamp: "14:16", value: 537.88 },
  { timestamp: "14:17", value: 522.76 },
  { timestamp: "14:18", value: 507.65 },
  { timestamp: "14:19", value: 492.58 },
  { timestamp: "14:20", value: 477.49 },
  { timestamp: "14:21", value: 462.37 },
  { timestamp: "14:22", value: 447.25 },
  { timestamp: "14:23", value: 432.18 },
  { timestamp: "14:24", value: 417.05 },
  { timestamp: "14:25", value: 402.01 },
  { timestamp: "14:26", value: 387.12 },
  { timestamp: "14:27", value: 372.44 },
  { timestamp: "14:28", value: 357.99 },
  { timestamp: "14:29", value: 343.65 },
  { timestamp: "14:30", value: 329.42 },
];

const socket = io("http://localhost:4000");

export default function Home({}) {
  const [stats, setStats] = useState({
    total1h: 329.35,
    peak1h: 395.31,
    districts1h: {
      dist1: 352.32,
      dist2: 412.35,
      dist3: 393.23,
    },
    maxDistrict: { key: "Dist1", value: 329.42 },
  });
  const [city1m, setCity1m] = useState(sample_data);
  const [dist1mMap, setDist1mMap] = useState({
    dist1: sample_data,
    dist2: sample_data,
    dist3: sample_data,
  });
  const [selected, setSelected] = useState("dist1");

  function handleDistrictChange() {
    const next =
      selected === "dist1" ? "dist2" : selected === "dist2" ? "dist3" : "dist1";
    setSelected(next);
  }

  useEffect(() => {
    socket.on("stats1h", (data) => setStats(data));
    socket.on("city1m", (newBuf) => setCity1m(newBuf));
    ["dist1", "dist2", "dist3"].forEach((d) =>
      socket.on(d + "1m", (buf) => setDist1mMap((m) => ({ ...m, [d]: buf }))),
    );

    return () => {
      socket.off("stats1h");
      socket.off("city1m");
      ["dist1", "dist2", "dist3"].forEach((d) => socket.off(d + "1m"));
    };
  }, []);

  return (
    <Box
      sx={{
        display: "flex",
        alignSelf: "center",
        justifyContent: "center",
        alignItems: "center",
        flexDirection: "column",
        height: "100%",
        width: "100%",
      }}
    >
      <Paper
        elevation={8}
        sx={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "row",
          justifyContent: "center",
          alignItems: "center",
        }}
      >
        <Grid
          container
          spacing={2}
          sx={{ width: "100%", height: "100%", p: 1.5 }}
        >
          <Grid size={{ xs: 12, md: 12 }}>
            <Box
              sx={{
                height: "100%",
                width: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "space-between",
              }}
            >
              <Box
                sx={{
                  height: "10%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}
              >
                <SmallInfoWidget
                  title="Total Power Consumption"
                  sub="Last 1h"
                  value={stats.total1h + " kWh"}
                />
                <SmallInfoWidget
                  title="Peak Load"
                  sub="Last 1h"
                  value={stats.peak1h + " kWh"}
                />
                <SmallInfoWidget
                  title="Top District"
                  sub="Last 1h"
                  value={`${stats.maxDistrict.key}: ${stats.maxDistrict.value + " kWh"}`}
                />
              </Box>
              <Box
                sx={{
                  height: "40%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}
              >
                <PieChartPaper label="City consumption distribution">
                  <CityPieChart
                    data={[
                      { name: "District 1", value: stats.districts1h.dist1 },
                      { name: "District 2", value: stats.districts1h.dist2 },
                      { name: "District 3", value: stats.districts1h.dist3 },
                    ]}
                  />
                </PieChartPaper>
                <LineChartPaper label="City consumption">
                  <CityLineChart data={city1m} />
                </LineChartPaper>
              </Box>
              {/*<Box
                sx={{
                  height: "3%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "flex-start",
                }}
              >
                <FormControl fullWidth>
                  <InputLabel id="district-select-label">District</InputLabel>
                  <Select
                    labelId="district-select-label"
                    id="district-select"
                    value={selected}
                    label="District"
                    onChange={handleDistrictChange}
                  >
                    <MenuItem value="dist1">District 1</MenuItem>
                    <MenuItem value="dist2">District 2</MenuItem>
                    <MenuItem value="dist3">District 3</MenuItem>
                  </Select>
                </FormControl>
              </Box> */}
              <Box
                sx={{
                  height: "40%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}
              >
                <LineChartPaper width="32%" label="District 1 consumption">
                  <DistLineChart
                    data={dist1mMap["dist1"]}
                    // label="district 1 consumption"
                  />
                </LineChartPaper>
                <LineChartPaper width="32%" label="District 2 consumption">
                  <DistLineChart
                    data={dist1mMap["dist2"]}
                    // label="district 2 consumption"
                  />
                </LineChartPaper>
                <LineChartPaper width="32%" label="District 3 consumption">
                  <DistLineChart
                    data={dist1mMap["dist3"]}
                    // label="District 3 consumption"
                  />
                </LineChartPaper>
              </Box>
            </Box>
          </Grid>
          {/*<Grid size={{ xs: 12, md: 3 }}>
            <Paper
              sx={{
                height: "100%",
                width: "100%",
                display: "flex",
              }}
            >
              Power consumption each area
            </Paper>
          </Grid>*/}
        </Grid>
      </Paper>
    </Box>
  );
}

// ---------------------------------------------------------------------------
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
      }}
    >
      <Box
        sx={{
          height: "100%",
          width: "70%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "flex-start",
        }}
      >
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
        }}
      >
        <Typography color=".">{value}</Typography>
      </Box>
    </Paper>
  );
}

function PieChartPaper({ children, label }) {
  return (
    <Paper
      sx={{
        height: "100%",
        width: "38%",
        display: "flex",
        justifyContent: "center",
        flexDirection: "column",
        alignItems: "center",
      }}
    >
      {children}
      <Typography color="primary.dark">{label}</Typography>
    </Paper>
  );
}

function LineChartPaper({ children, label, width = "60%" }) {
  return (
    <Paper
      sx={{
        height: "100%",
        width: { width },
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      {children}
      <Typography color="primary.dark">{label}</Typography>
    </Paper>
  );
}

// ------------------------ Chart Components -------------------------------

export function CityPieChart({ data, label }) {
  return (
    <ResponsiveContainer width="100%" height="90%">
      <RechartsPieChart>
        <Pie
          data={data}
          dataKey="value"
          nameKey="name"
          cx="50%"
          cy="50%"
          outerRadius={100}
          label
        >
          {data.map((entry, index) => (
            <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
          ))}
        </Pie>
        <RechartsTooltip />
        <Legend layout="vertical" verticalAlign="middle" align="right" />
      </RechartsPieChart>
    </ResponsiveContainer>
  );
}

export function CityLineChart({ data, label }) {
  return (
    <ResponsiveContainer width="100%" height="90%">
      <RechartsLineChart data={data}>
        <XAxis dataKey="timestamp">
          <Label value={label} offset={0} position="insideBottom" />
        </XAxis>
        <YAxis />
        <CartesianGrid strokeDasharray="3 3" />
        <RechartsTooltip />
        <Line type="monotone" dataKey="value" dot={false} />
      </RechartsLineChart>
    </ResponsiveContainer>
  );
}

export function DistLineChart({ data, label }) {
  return (
    <ResponsiveContainer width="100%" height="90%">
      <RechartsLineChart data={data}>
        <XAxis dataKey="timestamp">
          <Label value={label} offset={0} position="insideBottom" />
        </XAxis>
        <YAxis />
        <CartesianGrid strokeDasharray="4 2" />
        <RechartsTooltip />
        <Label value={label} offset={0} position="insideBottom" />
        <Line type="monotone" dataKey="value" dot={false} />
      </RechartsLineChart>
    </ResponsiveContainer>
  );
}
