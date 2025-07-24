# Rinha de Backend - 2025

Código fonte da submissão feita para a [Rinha de Backend - 2025](https://github.com/zanfranceschi/rinha-de-backend-2025). Para essa submissão, foi utilizada as seguintes stacks:

- [Linguagem de programação Scala](https://scala-lang.org/) para o desenvolvimento das aplicações.
- [ZIO](https://zio.dev/) como sistema de efeitos monádicos.
- [ZIO HTTP](https://zio.dev/zio-http/) para o desenvolvimento dos endpoints da aplicação.
- [Valkey](https://valkey.io/) como banco de dados em memória.
- [GraalVM](https://www.graalvm.org/) para a criação de imagens nativas a fim de otimizar o consumo excessivo de memória da JVM.

## Arquitetura

- As aplicações recebem uma solicitação de pagamento no endpoint _/payments_ e adiciona o pagamento em uma [queue](https://zio.dev/reference/concurrency/queue/) thread safe.
- Um processo rodando indefinitivamente em background é responsável por consumir os pagamentos dessa fila e enviá-los, um por vez, aos _payment processors_ e ao banco de dados em memória, nesse caso, ao [Valkey](https://valkey.io/).
- Os pagamentos são armazenados no Valkey através de duas [sorted sets](https://valkey.io/topics/sorted-sets/). Uma sorted set armazena os pagamentos enviados ao payment processor _default_, enquanto que a outra sorted set armazena os pagamentos enviados ao payment processor _fallback_.
- Os Sorted Sets requerem um _score_ que é associado a cada elemento inserido na coleção. Esses scores são utilizados como chave de ordenação. Os scores podem ser definidos automaticamente pelo Valkey, ou manualmente. Para essa implementação, utilizou-se o timestamp da solicitação HTTP ao endpoint de _payments_. Em outras palavras, o campo _requestedAt_. O timestamp foi convertido para um valor _unix epoch_, o qual é um valor numérico que pode ser utilizado como score dos Sorted Sets. Os elementos inseridos nos Sorted Sets foram uma versão _reduzida_ da representação _JSON_ do objeto _payment_.
- Por fim, quando uma solicitação HTTP é enviada ao endpoint _payments-summary_, as APIs converte o intervalo de datas (_from_ e _to_), para o valor numérico associado ao Unix Epoch. Com base nesses valores numéricos, que são os scores dos Sorted Sets, é extraído todos os pagamentos inseridos naquele determinado intervalo de datas.

## Otimizações

As aplicações desenvolvidas estavam consumindo aproximadamente 200MB de memória durante os testes. Como a Rinha restringia os serviços a 350MB de memória, buscou-se uma solução para a redução do consumo de memória e até mesmo a redução do consumo de CPU.

A solução encontrada foi compilar as aplicações para [GraalVM](https://www.graalvm.org/) e a criação de [imagens nativas](https://www.graalvm.org/latest/reference-manual/native-image/). As imagens nativas oferecem uma solução muito mais viável do que a utilização da JVM, visto que o consumo de memória cai praticamente pela metade. Após a geração da imagem nativa da aplicação, o consumo de memória caiu de 200MB para apenas 70MB.

Como os recursos didáticos para a criação de imagens nativas de aplicações escritas em Scala são escassos, reproduzo aqui as etapas utilizadas:

1. Instalação do GraalVM na máquina local. Não utilizar o recurso para compilar para GraalVM disponível através do [Scala CLI](https://scala-cli.virtuslab.org/). Instalou-se o GraalVM através do [SDKMAN](https://sdkman.io/).

   ```bash
   $ sdk current
   java 21.0.2-graalce
   ```

2. Criar um arquivo _.jar_ da aplicação. Esse processo pode ser feito através do Scala CLI:

   ```bash
   scala-cli package . --assembly -o app.jar
   ```

3. Como a aplicação utiliza bibliotecas que possuem muita utilização de _reflections_ e _macros_, é necessário executar o _Native Image Agent_ para que essas reflections sejam totalmente compreendidas pelo GraalVM em tempo de execução. O native image agente deve ser executado com base no arquivo .jar gerado na etapa anterior.

   ```bash
   java -agentlib:native-image-agent=config-output-dir=graalvm-config -jar app.jar
   ```

4. Por fim, basta executar o _Native Image_ para a criação da imagem nativa da aplicação.

   ```bash
   native-image \
     --no-fallback \
     --enable-http \
     --enable-https \
     -H:ConfigurationFileDirectories=graalvm-config \
     -jar app.jar
   ```

> Observação: não foi possível gerar a imagem nativa da aplicação por meio de uma imagem Docker. Foi necessário gerar a imagem nativa na máquina local, e depois copiar o executável para dentro de um contêiner Ubuntu. Vide o arquivo _Dockerfile_ para mais detalhes.
