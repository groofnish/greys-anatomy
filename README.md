![LOGO icon](https://raw.githubusercontent.com/oldmanpushcart/images/master/greys/greys-logo-readme.png)

>
线上系统为何经常出错？数据库为何屡遭黑手？业务调用为何频频失败？连环异常堆栈案，究竟是那次调用所为？
数百台服务器意外雪崩背后又隐藏着什么？是软件的扭曲还是硬件的沦丧？
走进科学带你了解Greys, Java线上问题诊断工具。

# 相关文档

* [关于软件](https://github.com/oldmanpushcart/greys-anatomy/wiki/Home)
* [程序安装](https://github.com/oldmanpushcart/greys-anatomy/wiki/installing)
* [入门说明](https://github.com/oldmanpushcart/greys-anatomy/wiki/Getting-Start)
* [常见问题](https://github.com/oldmanpushcart/greys-anatomy/wiki/FAQ)
* [更新记事](https://github.com/oldmanpushcart/greys-anatomy/wiki/Chronicle)
* [详细文档](https://github.com/oldmanpushcart/greys-anatomy/wiki/greys-pdf)

# 程序安装

- 远程安装

  ```shell
  curl -sLk http://ompc.oss.aliyuncs.com/greys/install.sh|sh
  ```
  
- 远程安装(短链接)
  
  ```shell
  curl -sLk http://t.cn/R2QbHFc|sh
  ```

## 最新版本

### **VERSION :** 1.7.3.0

更新内容

* 因为我对Groovy了解不足,导致Groovy在使用过程中会存在Perm区泄漏.为了规避这个问题,我用OGNL表达式替换了Groovy
* Groovy去掉之后,`groovy`命令也随之不再支持,等有人帮我解决了泄漏后续会加上
* 修复`watch`命令对`-b`/`-s`/`-e`/`-f`识别混乱的bug
* 重构了textui

### 版本号说明

`主版本`.`大版本`.`小版本`.`漏洞修复`

* 主版本

  这个版本更新说明程序架构体系进行了重大升级，比如之前的0.1版升级到1.0版本，整个软件的架构从单机版升级到了SOCKET多机版。并将Greys的性质进行的确定：Java版的HouseMD，但要比前辈们更强。

* 大版本

  程序的架构设计进行重大改造，但不影响用户对这款软件的定位。

* 小版本

  增加新的命令和功能

* 漏洞修复

  对现有版本进行漏洞修复和增强
  
  - `主版本`、`大版本`、之间不做任何向下兼容的承诺，即`0.1`版本的Client不保证一定能正常访问`1.0`版本的Server。

  - `小版本`不兼容的版本会在版本升级中指出

  - `漏洞修复`保证向下兼容

# 维护者

* [李夏驰](http://www.weibo.com/vlinux)
* [姜小逸又胖了](http://weibo.com/chengtd)


# 程序编译

- 打开终端

  ```shell
  git clone git@github.com:oldmanpushcart/greys-anatomy.git
  cd greys-anatomy/bin
  ./greys-packages.sh
  ```
  
- 程序执行

  在`target/`目录下生成对应版本的release文件，比如当前版本是`1.7.0.4`，则生成文件`target/greys-1.7.0.4-bin.zip`
  
  程序在本地编译时会主动在本地安装当前编译的版本，所以编译完成后即相当在本地完成了安装。