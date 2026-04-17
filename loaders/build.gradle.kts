plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
}

dependencies {
    compileOnly(project(":btccore-api"))

    api(project(":loaders:api-loader"))
    api(project(":loaders:file-loader"))
    api(project(":loaders:mongo-loader"))
    api(project(":loaders:mysql-loader"))
    api(project(":loaders:redis-loader"))

    compileOnly(paperApi())
}

publishConfiguration {
    name = "BTC-Core Loaders"
    description = "Default loaders for BTC-Core. There might be more loaders available then included in this BOM package"
}

