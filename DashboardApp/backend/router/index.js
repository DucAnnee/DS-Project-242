import express from "express";
import userRoutes from "./user.routes.js";
import sampleRoutes "./sample.routes.js";

const router = express.Router();

router.use("/authentication", userRoutes);
router.use("/sample", sampleRoutes);

export default router;
