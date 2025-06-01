import React, { useEffect, useState } from "react";
import { Grid, Box, Paper, Typography } from "@mui/material";
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
import { usePersistedState } from "../components/usePersistentState";

// Color palette for Pie charts
const COLORS = ["#0088FE", "#00C49F", "#FFBB28", "#FF8042"];
const socket = io("http://localhost:4000");
socket.on("connect", () => console.log("✅ connected", socket.id));
socket.on("disconnect", () => console.log("❌ disconnected"));

const INIT_STATS = {
  total1h: "--",
  peak1h: "--",
  districts1h_sum: { district1: 0, district2: 0 },
  maxDistrict: { key: "--", value: 0 },
};
const INIT_CITY1M = [];
const INIT_DIST1MMAP = { district1: [], district2: [] };

export default function Home() {
  const [stats, setStats] = usePersistedState("dash_stats", INIT_STATS);
  const [city1m, setCity1m] = usePersistedState("dash_city1m", INIT_CITY1M);
  const [dist1mMap, setDist1mMap] = usePersistedState(
    "dash_dist1mMap",
    INIT_DIST1MMAP,
  );

  useEffect(() => {
    socket.on("stats1h", (data) => {
      console.log("📥 stats1h received");
      setStats({
        total1h: data.total1h,
        peak1h: data.peak1h,
        districts1h_sum: data.districts1h_sum ?? {},
        maxDistrict: data.maxDistrict ?? { key: "--", value: 0 },
      });
    });
    socket.on("city1m", (buf) => {
      console.log("📥 city1m received", buf.length);
      const normalized = buf.map((p) => ({
        timestamp: p.timestamp ?? p.date,
        value: p.value,
      }));
      setCity1m(normalized);
    });
    ["district1", "district2"].forEach((d) =>
      socket.on(`${d}1m`, (buf) => {
        console.log("📥 district received", buf.length);
        const normalized = buf.map((p) => ({
          timestamp: p.timestamp ?? p.date,
          value: p.value,
        }));
        setDist1mMap((m) => ({ ...m, [d]: normalized }));
      }),
    );

    return () => {
      socket.off("stats1h");
      socket.off("city1m");
      ["district1", "district2", "district3"].forEach((d) =>
        socket.off(`${d}1m`),
      );
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
                      {
                        name: "District 1",
                        value: stats.districts1h_sum.district1,
                      },
                      {
                        name: "District 2",
                        value: stats.districts1h_sum.district2,
                      },
                    ]}
                  />
                </PieChartPaper>
                <LineChartPaper label="City consumption">
                  <CityLineChart data={city1m} />
                </LineChartPaper>
              </Box>
              <Box
                sx={{
                  height: "40%",
                  width: "100%",
                  display: "flex",
                  justifyContent: "space-between",
                }}
              >
                <LineChartPaper width="49%" label="District 1 consumption">
                  <DistLineChart
                    data={dist1mMap["district1"]}
                    // label="district 1 consumption"
                  />
                </LineChartPaper>
                <LineChartPaper width="49%" label="District 2 consumption">
                  <DistLineChart
                    data={dist1mMap["district2"]}
                    // label="district 2 consumption"
                  />
                </LineChartPaper>
              </Box>
            </Box>
          </Grid>
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
