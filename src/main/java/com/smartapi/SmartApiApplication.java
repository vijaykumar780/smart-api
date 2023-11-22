package com.smartapi;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.angelbroking.smartapi.models.OrderParams;
import org.json.JSONObject;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartapi.pojo.LoginResponse;

import lombok.extern.log4j.Log4j2;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Log4j2
@EnableAsync
@EnableScheduling
public class SmartApiApplication {

	private static ConfigurableApplicationContext context;

	public static void main(String[] args) throws UnknownHostException {
		context = SpringApplication.run(SmartApiApplication.class, args);
		log.info(Constants.IMP_LOG+"Started jar at address {}, Build {}", InetAddress.getLocalHost(), Constants.build);

		log.info("\nVVVVVVVV           VVVVVVVV  iiii               jjjj                                            \n" +
				"V::::::V           V::::::V i::::i             j::::j                                           \n" +
				"V::::::V           V::::::V  iiii               jjjj                                            \n" +
				"V::::::V           V::::::V                                                                     \n" +
				" V:::::V           V:::::V iiiiiii            jjjjjjj  aaaaaaaaaaaaa   yyyyyyy           yyyyyyy\n" +
				"  V:::::V         V:::::V  i:::::i            j:::::j  a::::::::::::a   y:::::y         y:::::y \n" +
				"   V:::::V       V:::::V    i::::i             j::::j  aaaaaaaaa:::::a   y:::::y       y:::::y  \n" +
				"    V:::::V     V:::::V     i::::i             j::::j           a::::a    y:::::y     y:::::y   \n" +
				"     V:::::V   V:::::V      i::::i             j::::j    aaaaaaa:::::a     y:::::y   y:::::y    \n" +
				"      V:::::V V:::::V       i::::i             j::::j  aa::::::::::::a      y:::::y y:::::y     \n" +
				"       V:::::V:::::V        i::::i             j::::j a::::aaaa::::::a       y:::::y:::::y      \n" +
				"        V:::::::::V         i::::i             j::::ja::::a    a:::::a        y:::::::::y       \n" +
				"         V:::::::V         i::::::i            j::::ja::::a    a:::::a         y:::::::y        \n" +
				"          V:::::V          i::::::i            j::::ja:::::aaaa::::::a          y:::::y         \n" +
				"           V:::V           i::::::i            j::::j a::::::::::aa:::a        y:::::y          \n" +
				"            VVV            iiiiiiii            j::::j  aaaaaaaaaa  aaaa       y:::::y           \n" +
				"                                               j::::j                        y:::::y            \n" +
				"                                     jjjj      j::::j                       y:::::y             \n" +
				"                                    j::::jj   j:::::j                      y:::::y              \n" +
				"                                    j::::::jjj::::::j                     y:::::y               \n" +
				"                                     jj::::::::::::j                     yyyyyyy                \n" +
				"                                       jjj::::::jjj                                             \n" +
				"                                          jjjjjj                                                \n" +
				"                                                                                                \n" +
				"                                                                                                \n" +
				"               AAA               lllllll                                                        \n" +
				"              A:::A              l:::::l                                                        \n" +
				"             A:::::A             l:::::l                                                        \n" +
				"            A:::::::A            l:::::l                                                        \n" +
				"           A:::::::::A            l::::l    ggggggggg   ggggg   ooooooooooo                     \n" +
				"          A:::::A:::::A           l::::l   g:::::::::ggg::::g oo:::::::::::oo                   \n" +
				"         A:::::A A:::::A          l::::l  g:::::::::::::::::go:::::::::::::::o                  \n" +
				"        A:::::A   A:::::A         l::::l g::::::ggggg::::::ggo:::::ooooo:::::o                  \n" +
				"       A:::::A     A:::::A        l::::l g:::::g     g:::::g o::::o     o::::o                  \n" +
				"      A:::::AAAAAAAAA:::::A       l::::l g:::::g     g:::::g o::::o     o::::o                  \n" +
				"     A:::::::::::::::::::::A      l::::l g:::::g     g:::::g o::::o     o::::o                  \n" +
				"    A:::::AAAAAAAAAAAAA:::::A     l::::l g::::::g    g:::::g o::::o     o::::o                  \n" +
				"   A:::::A             A:::::A   l::::::lg:::::::ggggg:::::g o:::::ooooo:::::o                  \n" +
				"  A:::::A               A:::::A  l::::::l g::::::::::::::::g o:::::::::::::::o                  \n" +
				" A:::::A                 A:::::A l::::::l  gg::::::::::::::g  oo:::::::::::oo                   \n" +
				"AAAAAAA                   AAAAAAAllllllll    gggggggg::::::g    ooooooooooo                     \n" +
				"                                                     g:::::g                                    \n" +
				"                                         gggggg      g:::::g                                    \n" +
				"                                         g:::::gg   gg:::::g                                    \n" +
				"                                          g::::::ggg:::::::g                                    \n" +
				"                                           gg:::::::::::::g                                     \n" +
				"                                             ggg::::::ggg                                       \n" +
				"                                                gggggg                                          ");
	}

	public static void restart() {
		ApplicationArguments args = context.getBean(ApplicationArguments.class);

		Thread thread = new Thread(() -> {
			context.close();
			context = SpringApplication.run(SmartApiApplication.class, args.getSourceArgs());
		});

		thread.setDaemon(false);
		thread.start();
	}
}
