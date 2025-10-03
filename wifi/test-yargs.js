console.log(require("yargs"));
const yargs = require("yargs/yargs");
const { hideBin } = require("yargs/helpers");

const argv = yargs(hideBin(process.argv))
.option("test", {
    type: "boolean",
    description: "Test option",
})
.argv;

console.log(argv);
