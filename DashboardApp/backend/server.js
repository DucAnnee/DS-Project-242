import app from '../Server/app.js'
import dbconnection from './config/db.config.js';
import dotenv from 'dotenv';
dotenv.config();
const port = process.env.PORT || 5000;
app.listen(port, () => {
  console.log(`App running on port ${port}...`);
});