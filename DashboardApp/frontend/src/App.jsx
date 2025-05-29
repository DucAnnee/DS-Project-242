import { Outlet } from "react-router-dom";
import Appbar from "./components/Appbar";

function App() {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        height: "100vh",
        width: "100vw",
      }}
    >
      <Appbar />
      <div
        style={{ alignSelf: "center", flex: 1, height: "100%", width: "100%" }}
      >
        <Outlet />
      </div>
    </div>
  );
}

export default App;
