const get_standard_datetime = (datetime) => {
  const date = new Date(datetime);

  const standard_datetime = date.toLocaleString('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  });
  
  return standard_datetime;
}


export default get_standard_datetime;