const { Kafka } = require("kafkajs");
const express = require("express");
const http = require("http");
const socketIO = require("socket.io");

const BROKER = "localhost:9092";
const PORT = 4000;
const TOPICS = [
  "city.total.1h",
  "city.peak.1h",
  "dist1.total.1h",
  "dist2.total.1h",
  "dist3.total.1h",
  "city.total.1m",
  "dist1.total.1m",
  "dist2.total.1m",
  "dist3.total.1m",
];

async function start() {
  // Kafka setup
  const kafka = new Kafka({ brokers: [BROKER] });
  const consumer = kafka.consumer({ groupId: "dashboard-group" });
  await consumer.connect();
  for (const topic of TOPICS) {
    await consumer.subscribe({ topic, fromBeginning: false });
  }

  // HTTP + WebSocket
  const app = express();
  const server = http.createServer(app);
  const io = socketIO(server, { cors: { origin: "*" } });

  // In‐memory state
  const state = {
    total1h: null,
    peak1h: null,
    districts1h: { dist1: null, dist2: null, dist3: null },
    city1m: [],
    districts1m: { dist1: [], dist2: [], dist3: [] },
  };

  const appendAndTrim = (arr, point) => {
    arr.push(point);
    if (arr.length > 60) arr.shift();
  };

  await consumer.run({
    eachMessage: async ({ topic, message }) => {
      const { timestamp, value } = JSON.parse(message.value.toString());

      // 1h topics
      if (topic === "city.total.1h") {
        state.total1h = value;
      } else if (topic === "city.peak.1h") {
        state.peak1h = value;
      } else if (/^dist[123]\.total\.1h$/.test(topic)) {
        const d = topic.split(".")[0]; // 'dist1' | 'dist2' | 'dist3'
        state.districts1h[d] = value;
      }

      // 1m topics
      else if (topic === "city.total.1m") {
        appendAndTrim(state.city1m, { timestamp, value });
        io.emit("city1m", state.city1m);
      } else if (/^dist[123]\.total\.1m$/.test(topic)) {
        const d = topic.split(".")[0];
        appendAndTrim(state.districts1m[d], { timestamp, value });
        io.emit(`${d}1m`, state.districts1m[d]);
      }

      // Compute max district
      const maxDistrict = Object.entries(state.districts1h).reduce(
        (best, [key, val]) => (val > best.value ? { key, value: val } : best),
        { key: null, value: -Infinity },
      );

      // Emit aggregated stats including the full map
      io.emit("stats1h", {
        total1h: state.total1h,
        peak1h: state.peak1h,
        districts1h: state.districts1h, // <-- now included
        maxDistrict,
      });
    },
  });

  server.listen(PORT, () =>
    console.log(`WS server listening on http://localhost:${PORT}`),
  );
}

start().catch(console.error);
