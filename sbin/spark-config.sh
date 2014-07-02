#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# included in all the spark scripts with source command
# should not be executable directly
# also should not be passed any arguments, since we need original $*

# resolve links - $0 may be a softlink
# 第一步取得当前脚本的绝对路径（含脚本名），赋值给this
# ${BASH_SOURCE-$0}获取的是spark_config文件的路径名称，而$0则是嵌入脚本的名称
this="${BASH_SOURCE-$0}"
common_bin=$(cd -P -- "$(dirname -- "$this")" && pwd -P)
script="$(basename -- "$this")"
this="$common_bin/$script"

# convert relative path to absolute path
# Todo:这个相对路径转换还需要么？上面已经是绝对路径
config_bin=`dirname "$this"`
script=`basename "$this"`
config_bin=`cd "$config_bin"; pwd`
this="$config_bin/$script"

# 第二步定义SPARK_HOME系列变量，同时将PySpark加入到PYTHONPATH环境变量中
export SPARK_PREFIX=`dirname "$this"`/..
export SPARK_HOME=${SPARK_PREFIX}
export SPARK_CONF_DIR="$SPARK_HOME/conf"
# Add the PySpark classes to the PYTHONPATH:
export PYTHONPATH=$SPARK_HOME/python:$PYTHONPATH
export PYTHONPATH=$SPARK_HOME/python/lib/py4j-0.8.1-src.zip:$PYTHONPATH
