plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))

    api(libs.lettuce)

    compileOnly(paperApi())
}

publishConfiguration {
    name = "BTC-Core Redis Loader"
    description = "Redis loader for BTC-Core"
}


