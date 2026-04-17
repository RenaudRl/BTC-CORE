plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))
    compileOnly(paperApi())

    api(libs.mongo)
}

publishConfiguration {
    name = "BTC-Core MongoDB Loader"
    description = "MongoDB GridFS Loader for BTC-Core"
}


