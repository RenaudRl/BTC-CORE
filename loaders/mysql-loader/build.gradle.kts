plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))

    api(libs.hikari)
    compileOnly(paperApi())
}

publishConfiguration {
    name = "BTC-Core MySQL Loader"
    description = "MySQL loader for BTC-Core"
}


