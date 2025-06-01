const { Kafka } = require("kafkajs");
const express = require("express");
const http = require("http");
const socketIO = require("socket.io");

const BROKER = "52.229.164.8:9091";
const PORT = 4000;
const TOPICS = [
  "district1.1h.sum",
  "district2.1h.sum",
  "city.1h.sum",
  "district1.1m.sum",
  "district2.1m.sum",
  "city.1m.sum",
  "district1.1h.peak",
  "district2.1h.peak",
  "city.1h.peak",
];

async function start() {
  // Kafka setup
  const kafka = new Kafka({ brokers: [BROKER] });
  const consumer = kafka.consumer({ groupId: "dashboard-group-00" });
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
    total1h: 0,
    peak1h: 0,
    city1m: [],
    districts1h_sum: { district1: 0, district2: 0 },
    districts1h_peak: { district1: 0, district2: 0 },
    districts1m_sum: { district1: [], district2: [] },
  };

  const appendAndTrim = (arr, point) => {
    arr.push(point);
    if (arr.length > 60) arr.shift();
  };

  await consumer.run({
    partitionsConsumedConcurrently: 9,
    eachMessage: async ({ topic, message }) => {
      if (!message.value) return; // skip tombstones

      let payload;
      try {
        payload = JSON.parse(message.value.toString());
      } catch (e) {
        console.error("Bad JSON", topic, message.offset);
        return;
      }

      // 1h topics
      if (topic === "city.1h.sum") {
        const value = payload.hourly_total_consumption;
        state.total1h = value;
      } else if (topic === "city.1h.peak") {
        const value = payload.peak_household_consumption;
        state.peak1h = value;
      } else if (/^district[12]\.1h\.sum$/.test(topic)) {
        const value = payload.hourly_total_consumption;
        const d = topic.split(".")[0]; // 'district1' | 'district2'
        state.districts1h_sum[d] = value;
      } else if (/^district[12]\.1h\.peak$/.test(topic)) {
        const d = topic.split(".")[0];
        state.districts1h_peak[d] = payload.peak_household_consumption;
      }

      // 1m topics
      else if (topic === "city.1m.sum") {
        const timestamp = payload.timestamp_minute;
        // convert timestamp to HH:MM format
        const date = new Date(timestamp).toISOString().slice(11, 16);

        const value = payload.total_consumption;
        appendAndTrim(state.city1m, { date, value });
        // console.log("Updated city1m:", state.city1m);
        io.emit("city1m", state.city1m);
      } else if (/^district[12]\.1m\.sum$/.test(topic)) {
        const timestamp = payload.timestamp_minute;
        // convert timestamp to HH:MM format
        const date = new Date(timestamp).toISOString().slice(11, 16);

        const value = payload.total_consumption;
        const d = topic.split(".")[0];
        appendAndTrim(state.districts1m_sum[d], { date, value });
        // console.log(`Updated ${d}1m:`, state.districts1m_sum[d]);
        io.emit(`${d}1m`, state.districts1m_sum[d]);
      }

      // Compute most consuming district
      const maxDistrict = Object.entries(state.districts1h_sum).reduce(
        (best, [key, val]) =>
          val != null && val > best.value ? { key, value: val } : best,
        { key: null, value: -Infinity },
      );

      // Emit aggregated stats including the full map
      io.emit("stats1h", {
        total1h: state.total1h,
        peak1h: state.peak1h,
        districts1h_sum: state.districts1h_sum, // <-- now included
        districts1h_peak: state.districts1h_peak,
        maxDistrict,
      });

      // Log the state
      // console.log(state);
    },
  });

  server.listen(PORT, () =>
    console.log(`WS server listening on http://localhost:${PORT}`),
  );
}

start().catch(console.error);
