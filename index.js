const { PythonShell } = require("python-shell");
const express = require("express");
const app = express();

app.get("/", (req, res) => {
	res.send("data");
});

let options = {
	pythonOptions: ["-u"],
	path: "C:/Users/B M PRAJWAL/Work/SIH/script.py",
	args: ["Prajwalsssjh"],
};

const getData = async () => {
	const data = await PythonShell.run("script.py", options);
	console.log(data);
};
getData();

const PORT = 5000;
const start = () => {
	try {
		app.listen(5000, () =>
			console.log(`Server is listening on port: ${PORT}`)
		);
	} catch (error) {
		console.log(error);
	}
};

start();
