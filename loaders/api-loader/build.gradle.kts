plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))
    compileOnly(paperApi())
}

publishConfiguration {
    name = "BTC-Core API loader"
    description = "HTTP-API based loader for BTC-Core"
}

