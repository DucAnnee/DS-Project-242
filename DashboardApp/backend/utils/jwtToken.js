import jwt from 'jsonwebtoken';
import dotenv from 'dotenv';
dotenv.config();

const signToken = (user,statusCode,res) => {
  const token= jwt.sign({id:user._id,role:user.role},process.env.JWT_SECRET,{
    expiresIn: Number(process.env.JWT_EXPIRES)*3600*1000
  });
  user.password=undefined;
  res.status(statusCode).json({
    status:"success",
    user,
    token
  });
}

export default signToken;

