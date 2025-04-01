import mongoose from 'mongoose'
import dotenv from 'dotenv';
dotenv.config();
const DB = process.env.DATABASE.replace('<PASSWORD>', process.env.DATABASE_PASSWORD);

const dbconnection = mongoose.connect(DB,{
})
.then(con => {
  console.log("DB connection successfully!");
});

export default dbconnection;