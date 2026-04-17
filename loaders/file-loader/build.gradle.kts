plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))
    compileOnly(paperApi())
    implementation(libs.zstd)
}

publishConfiguration {
    name = "BTC-Core File Loader"
    description = "File loader for BTC-Core"
}

