/*
 * This file is part of SPFBL.
 *
 * SPFBL is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPFBL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPFBL. If not, see <http://www.gnu.org/licenses/>.
 */
package net.spfbl.http;

import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.util.MailConnectException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import net.spfbl.core.Server;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import net.spfbl.core.Analise;
import net.spfbl.data.Block;
import net.spfbl.core.Client;
import static net.spfbl.core.Client.Permission.NONE;
import net.spfbl.core.Core;
import net.spfbl.core.Defer;
import net.spfbl.core.ProcessException;
import net.spfbl.core.Reverse;
import net.spfbl.core.User;
import net.spfbl.core.User.Query;
import net.spfbl.core.User.Situation;
import net.spfbl.data.Generic;
import net.spfbl.data.Ignore;
import net.spfbl.data.NoReply;
import net.spfbl.data.Provider;
import net.spfbl.data.Trap;
import net.spfbl.data.White;
import net.spfbl.spf.SPF;
import net.spfbl.spf.SPF.Distribution;
import net.spfbl.whois.Domain;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv6;
import net.tanesha.recaptcha.ReCaptcha;
import net.tanesha.recaptcha.ReCaptchaFactory;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Servidor de consulta em SPF.
 *
 * Este serviço responde a consulta e finaliza a conexão logo em seguida.
 *
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public final class ServerHTTP extends Server {

    private final String HOSTNAME;
    private final int PORT;
    private final HttpServer SERVER;

    private final HashMap<String,String> MAP = new HashMap<String,String>();

    public synchronized HashMap<String,String> getMap() {
        HashMap<String,String> map = new HashMap<String,String>();
        map.putAll(MAP);
        return map;
    }

    public synchronized String drop(String domain) {
        return MAP.remove(domain);
    }

    public synchronized boolean put(String domain, String url) {
        try {
            domain = Domain.normalizeHostname(domain, false);
            if (domain == null) {
                return false;
            } else if (url == null || url.equals("NONE")) {
                MAP.put(domain, null);
                return true;
            } else {
                new URL(url);
                if (url.endsWith("/spam/")) {
                    MAP.put(domain, url);
                    return true;
                } else {
                    return false;
                }
            }
        } catch (MalformedURLException ex) {
            return false;
        }
    }

    public synchronized void store() {
        try {
            long time = System.currentTimeMillis();
            File file = new File("./data/url.map");
            if (MAP.isEmpty()) {
                file.delete();
            } else {
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(MAP, outputStream);
                } finally {
                    outputStream.close();
                }
                Server.logStore(time, file);
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
    }

    public synchronized void load() {
        long time = System.currentTimeMillis();
        File file = new File("./data/url.map");
        if (file.exists()) {
            try {
                HashMap<String,String> map;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    map = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                MAP.putAll(map);
                Server.logLoad(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }
    
    private static byte CONNECTION_LIMIT = 16;
    
    public static void setConnectionLimit(String limit) {
        if (limit != null && limit.length() > 0) {
            try {
                setConnectionLimit(Integer.parseInt(limit));
            } catch (Exception ex) {
                Server.logError("invalid HTTP connection limit '" + limit + "'.");
            }
        }
    }
    
    public static void setConnectionLimit(int limit) {
        if (limit < 1 || limit > Byte.MAX_VALUE) {
            Server.logError("invalid HTTP connection limit '" + limit + "'.");
        } else {
            CONNECTION_LIMIT = (byte) limit;
        }
    }

    /**
     * Configuração e intanciamento do servidor.
     * @param port a porta HTTPS a ser vinculada.
     * @throws java.io.IOException se houver falha durante o bind.
     */
    public ServerHTTP(String hostname, int port) throws IOException {
        super("SERVERHTP");
        HOSTNAME = hostname;
        PORT = port;
        setPriority(Thread.NORM_PRIORITY);
        // Criando conexões.
        Server.logDebug("binding HTTP socket on port " + port + "...");
        SERVER = HttpServer.create(new InetSocketAddress(port), 0);
        SERVER.createContext("/", new AccessHandler());
        SERVER.setExecutor(Executors.newFixedThreadPool(CONNECTION_LIMIT)); // creates a default executor
        Server.logTrace(getName() + " thread allocation.");
    }
    
    public String getURL() {
        if (HOSTNAME == null) {
            return null;
        } else {
            return "http://" + HOSTNAME + (PORT == 80 ? "" : ":" + PORT) + "/";
        }
    }

    public String getDNSBLURL(Locale locale) {
        if (HOSTNAME == null) {
            return null;
        } else if (locale == null) {
            return "http://" + HOSTNAME + (PORT == 80 ? "" : ":" + PORT) + "/dnsbl/";
        } else {
            return "http://" + HOSTNAME + (PORT == 80 ? "" : ":" + PORT) + "/" + locale.getLanguage() + "/dnsbl/";
        }
    }

    public String getURL(Locale locale) {
        if (HOSTNAME == null) {
            return null;
        } else if (locale == null) {
            return "http://" + HOSTNAME + (PORT == 80 ? "" : ":" + PORT) + "/";
        } else {
            return "http://" + HOSTNAME + (PORT == 80 ? "" : ":" + PORT) + "/" + locale.getLanguage() + "/";
        }
    }

    private static String getOrigin(String address, Client client, User user) {
        String result = address;
        result += (client == null ? "" : " " + client.getDomain());
        result += (user == null ? "" : " " + user.getEmail());
        return result;
    }
    
    private static Client getClient(HttpExchange exchange) {
        InetSocketAddress socketAddress = exchange.getRemoteAddress();
        InetAddress address = socketAddress.getAddress();
        return Client.get(address);
    }

    private static String getRemoteAddress(HttpExchange exchange) {
        InetSocketAddress socketAddress = exchange.getRemoteAddress();
        InetAddress address = socketAddress.getAddress();
        return address.getHostAddress();
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String,Object> getParameterMap(HttpExchange exchange) throws IOException {
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "UTF-8");
        BufferedReader br = new BufferedReader(isr);
        String query = br.readLine();
        return getParameterMap(query);
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String,Object> getParameterMap(String query) throws UnsupportedEncodingException {
        if (query == null || query.length() == 0) {
            return null;
        } else {
            Integer otp = null;
            Long begin = null;
            TreeSet<String> identifierSet = new TreeSet<String>();
            HashMap<String,Object> map = new HashMap<String,Object>();
            String pairs[] = query.split("[&]");
            for (String pair : pairs) {
                String param[] = pair.split("[=]");
                String key = null;
                String value = null;
                if (param.length > 0) {
                    key = URLDecoder.decode(param[0], System.getProperty("file.encoding"));
                }
                if (param.length > 1) {
                    value = URLDecoder.decode(param[1], System.getProperty("file.encoding"));
                }
                if ("identifier".equals(key)) {
                    identifierSet.add(value);
                } else if ("otp".equals(key)) {
                    try {
                        otp = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        // Ignore.
                    }
                } else if ("begin".equals(key)) {
                    try {
                        begin = Long.parseLong(value);
                    } catch (NumberFormatException ex) {
                        // Ignore.
                    }
                } else if ("filter".equals(key)) {
                    if (value == null) {
                        map.put(key, "");
                    } else {
                        value = Core.removerAcentuacao(value);
                        value = value.replace(" ", "");
                        value = value.toLowerCase();
                        if (value.length() > 0) {
                            map.put(key, value);
                        }
                    }
                } else {
                    map.put(key, value);
                }
            }
            if (otp != null) {
                map.put("otp", otp);
            }
            if (begin != null) {
                map.put("begin", begin);
            }
            if (!identifierSet.isEmpty()) {
                map.put("identifier", identifierSet);
            }
            return map;
        }
    }
    
    private static class Language implements Comparable<Language> {
        
        private final Locale locale;
        private float q;
        
        private Language(String language) {
            language = language.replace('-', '_');
            int index = language.indexOf(';');
            if (index == -1) {
                locale = LocaleUtils.toLocale(language);
                q = 1.0f;
            } else {
                String value = language.substring(0,index).trim();
                locale = LocaleUtils.toLocale(value);
                try {
                    index = language.lastIndexOf('=') + 1;
                    value = language.substring(index).trim();
                    q = Float.parseFloat(value);
                } catch (NumberFormatException ex) {
                    q = 0.0f;
                }
            }
        }
        
        public Locale getLocale() {
            return locale;
        }

        public boolean isLanguage(String language) {
            return locale.getLanguage().equals(language);
        }
        
        @Override
        public int compareTo(Language other) {
            if (other == null) {
                return -1;
            } else if (this.q < other.q) {
                return 1;
            } else {
                return -1;
            }
        }
        
        @Override
        public String toString() {
            return locale.getLanguage();
        }
    }
    
    private static Locale getLocale(String acceptLanguage) {
        if (acceptLanguage == null) {
            return Locale.US;
        } else {
            TreeSet<Language> languageSet = new TreeSet<Language>();
            StringTokenizer tokenizer = new StringTokenizer(acceptLanguage, ",");
            while (tokenizer.hasMoreTokens()) {
                try {
                    Language language = new Language(tokenizer.nextToken());
                    languageSet.add(language);
                } catch (Exception ex) {
                }
            }
            for (Language language : languageSet) {
                if (language.isLanguage("en")) {
                    return language.getLocale();
                } else if (language.isLanguage("pt")) {
                    return language.getLocale();
                }
            }
            return Locale.US;
        }
    }
    
    private static Locale getLocale(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        String acceptLanguage = headers.getFirst("Accept-Language");
        return getLocale(acceptLanguage);
    }
    
    private static User getUser(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        String cookies = headers.getFirst("Cookie");
        if (cookies == null) {
            return null;
        } else {
            StringTokenizer tokenizer = new StringTokenizer(cookies, ";");
            while (tokenizer.hasMoreTokens()) {
                try {
                    String cookie = tokenizer.nextToken().trim();
                    if (cookie.startsWith("login=")) {
                        int index = cookie.indexOf('=');
                        String registry = Server.decrypt(cookie.substring(index + 1).trim());
                        StringTokenizer tokenizer2 = new StringTokenizer(registry, " ");
                        Date date = Server.parseTicketDate(tokenizer2.nextToken());
                        if (System.currentTimeMillis() - date.getTime() < 604800000) {
                            String email = tokenizer2.nextToken();
                            InetAddress ticketAddress = InetAddress.getByName(tokenizer2.nextToken());
                            if (exchange.getRemoteAddress().getAddress().equals(ticketAddress)) {
                                return User.get(email);
                            }
                        }
                    }
                } catch (Exception ex) {
                    // Nada deve ser feito.
                }
            }
            return null;
        }
    }
    
    private static final SimpleDateFormat DATE_FORMAT_COOKIE = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    
    private static String getDateExpiresCookie() {
        long time = System.currentTimeMillis() + 604800000;
        Date date = new Date(time);
        return DATE_FORMAT_COOKIE.format(date);
    }
    
    private static void setUser(HttpExchange exchange, User user) throws ProcessException {
        Headers headers = exchange.getResponseHeaders();
        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
        String registry = Server.getNewTicketDate() + " " + user.getEmail() + " " + remoteAddress.getHostAddress();
        String ticket = Server.encrypt(registry);
        String cookie = "login=" + ticket + "; expires=" + getDateExpiresCookie() + "; path=/";
        headers.add("Set-Cookie", cookie);
    }
    
    private static String getTempoPunicao(long failTime) {
        if ((failTime /= 1000) < 60) {
            return failTime + (failTime > 1 ? " segundos" : " segundo");
        } else if ((failTime /= 60) < 60) {
            return failTime + (failTime > 1 ? " minutos" : " minuto");
        } else if ((failTime /= 60) < 24) {
            return failTime + (failTime > 1 ? " dias" : " dia");
        } else {
            failTime /= 24;
            return failTime + (failTime > 1 ? " semanas" : " semana");
        }
    }
    
    private static boolean isValidDomainOrIP(String token) {
        if (Server.isValidTicket(token)) {
            return false;
        } else if (Subnet.isValidIP(token)) {
            return true;
        } else if (Domain.isHostname(token)) {
            return true;
        } else {
            return false;
        }
    }
    
    private static final File FOLDER = new File("./web/");
    
    public static File getWebFile(String name) {
        File file = new File(FOLDER, name);
        if (file.exists()) {
            return file;
        } else {
            return null;
        }
    }
    
    private static byte CONNECTION_ID = 1;

    private static class AccessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                long time = System.currentTimeMillis();
                Thread thread = Thread.currentThread();
                if (!thread.getName().startsWith("HTTP00")) {
                    thread.setName("HTTP00" + Core.CENTENA_FORMAT.format(CONNECTION_ID++));
                }
                String request = exchange.getRequestMethod();
                URI uri = exchange.getRequestURI();
                String command = uri.toString();
                Locale locale = getLocale(exchange);
                User user = getUser(exchange);
                Client client = getClient(exchange);
                File file;
                String clientEmail = client == null ? null : client.getEmail();
                String remoteAddress = getRemoteAddress(exchange);
                String origin = getOrigin(remoteAddress, client, user);
                command = URLDecoder.decode(command, "UTF-8");
                HashMap<String,Object> parameterMap = getParameterMap(exchange);
                Server.logTrace(request + " " + command + (parameterMap == null ? "" : " " + parameterMap));
                int langIndex = command.indexOf('/', 1);
                if (langIndex == 3 || langIndex == 4) {
                    // Language mode.
                    String lang = command.substring(1, langIndex).toLowerCase();
                    if (lang.equals("en")) {
                        locale = Locale.UK;
                    } else if (lang.equals("pt")) {
                        locale = new Locale("pt", "BR");
                    } else {
                        locale = Locale.US;
                    }
                    command = command.substring(langIndex);
                } else if (user != null) {
                    locale = user.getLocale();
                } else if (clientEmail != null) {
                    locale = Core.getDefaultLocale(clientEmail);
                }
                int code;
                String result;
                String type;
                if (client != null && client.addQuery() && client.isAbusing()) {
                    type = "ABUSE";
                    code = 500;
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        result = "O CIDR " + client.getCIDR() + " foi banido por abuso.";
                    } else {
                        result = "The CIDR " + client.getCIDR() + " is banned by abuse.";
                    }
                } else if (!Core.hasHostname()) {
                    type = "ERROR";
                    code = 500;
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        result = getMessageHMTL(
                                locale,
                                "Página de erro do SPFBL",
                                "O hostname deste sistema não foi definido no arquivo de configuração."
                        );
                    } else {
                        result = getMessageHMTL(
                                locale,
                                "SPFBL error page",
                                "The hostname of this system has not been defined in the configuration file."
                        );
                    }
                } else if (request.equals("POST")) {
                    if (command.equals("/")) {
                        type = "MMENU";
                        code = 200;
                        String message;
//                        parameterMap = getParameterMap(exchange);
                        if (parameterMap != null && parameterMap.containsKey("query")) {
                            String query = (String) parameterMap.get("query");
                            if (Subnet.isValidIP(query) || Domain.isHostname(query)) {
                                String url = Core.getURL(locale, query);
                                result = getRedirectHTML(locale, url);
                            } else {
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    message = "Consulta inválida";
                                } else {
                                    message = "Invalid query";
                                }
                                result = getMainHTML(locale, message, remoteAddress);
                            }
                        } else {
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                message = "Página principal do serviço SPFBL";
                            } else {
                                message = "This is SPFBL's main page";
                            }
                            result = getMainHTML(locale, message, remoteAddress);
                        }
                    } else if (command.startsWith("/favicon.ico")) {
                        type = "HTTPC";
                        code = 403;
                        result = "Forbidden\n";
                    } else if (command.startsWith("/robots.txt")) {
                        type = "HTTPC";
                        code = 403;
                        result = "Forbidden\n";
                    } else if (Domain.isEmail(command.substring(1))) {
                        String message;
                        String userEmail = command.substring(1).toLowerCase();
                        User userLogin = getUser(exchange);
                        if (userLogin != null && userLogin.isEmail(userEmail)) {
//                            parameterMap = getParameterMap(exchange);
                            Long begin = (Long) (parameterMap == null ? null : parameterMap.get("begin"));
                            String filter = (String) (parameterMap == null ? null : parameterMap.get("filter"));
                            message = getControlPanel(locale, userLogin, begin, filter);
                        } else if ((userLogin = User.get(userEmail)) == null) {
                            message = getMessageHMTL(
                                    locale,
                                    "Login do SPFBL",
                                    "Usuário inexistente."
                            );
                        } else if (userLogin.hasSecretOTP() || userLogin.hasTransitionOTP()) {
//                            parameterMap = getParameterMap(exchange);
                            if (parameterMap != null && parameterMap.containsKey("otp")) {
                                Integer otp = (Integer) parameterMap.get("otp");
                                if (userLogin.isValidOTP(otp)) {
                                    setUser(exchange, userLogin);
                                    message = getRedirectHTML(locale, command);
                                } else if (userLogin.tooManyFails()) {
                                    long failTime = userLogin.getFailTime();
                                    int pageTime = (int) (failTime / 1000) + 1;
                                    String tempoPunicao = getTempoPunicao(failTime);
                                    message = getRedirectHMTL(
                                            locale,
                                            "Login do SPFBL",
                                            "Conta temporariamente bloqueada por excesso de logins fracassados.\n"
                                            + "Aguarde cerca de " + tempoPunicao + " para tentar novamente.",
                                            command,
                                            pageTime
                                    );
                                } else if (userLogin.hasTransitionOTP()) {
                                    if (userLogin.hasSecretOTP()) {
                                        message = getLoginOTPHMTL(
                                                locale,
                                                "Página de login do SPFBL",
                                                "Para confirmar a mudança de senha "
                                                + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a>, "
                                                + "digite o valor da nova chave enviada por e-mail:"
                                        );
                                    } else {
                                        message = getLoginOTPHMTL(
                                                locale,
                                                "Página de login do SPFBL",
                                                "Para ativar a senha "
                                                + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                                + "da sua conta, digite o valor da chave enviada por e-mail:"
                                        );
                                    }
                                } else {
                                    message = getLoginOTPHMTL(
                                            locale,
                                            "A senha <a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                            + "inserida é inválida para esta conta",
                                            "Para ativar a autenticação "
                                            + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                            + "da sua conta, digite o valor da chave enviada por e-mail:"
                                    );
                                }
                            } else {
                                message = getLoginOTPHMTL(
                                        locale,
                                        "Página de login do SPFBL",
                                        "Para entrar no painel de controle, digite o valor da chave "
                                        + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                        + "de sua conta:"
                                );
                            }
                        } else {
//                            parameterMap = getParameterMap(exchange);
                            boolean valid = true;
                            if (Core.hasRecaptchaKeys()) {
                                if (parameterMap != null
                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                        && parameterMap.containsKey("recaptcha_response_field")
                                        ) {
                                    // reCAPCHA convencional.
                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                    if (recaptchaResponse == null) {
                                        valid = false;
                                    } else {
                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                        valid = response.isValid();
                                    }
                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                    // TODO: novo reCAPCHA.
                                    valid = false;
                                } else {
                                    // reCAPCHA necessário.
                                    valid = false;
                                }
                            }
                            if (valid) {
                                if (enviarOTP(locale, userLogin)) {
                                    message = getLoginOTPHMTL(
                                            locale,
                                            "Segredo "
                                            + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                            + "enviado com sucesso",
                                            "Para confirmar a mudança de senha "
                                            + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a>, "
                                            + "digite o valor do segredo enviado por e-mail:"
                                    );
                                } else {
                                    message = getMessageHMTL(
                                            locale,
                                            "Login do SPFBL",
                                            "Não foi possível enviar o segredo "
                                            + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a>."
                                    );
                                }
                            } else {
                                message = getSendOTPHMTL(
                                        locale,
                                        "Seu e-mail ainda não possui senha "
                                        + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                        + "neste sistema",
                                        "Para receber o segredo "
                                        + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                        + "em seu e-mail, resolva o reCAPTCHA abaixo."
                                );
                            }
                        }
                        type = "PANEL";
                        code = 200;
                        result = message;
                    } else if (Core.isLong(command.substring(1))) {
                        User userLogin = getUser(exchange);
                        if (userLogin == null) {
                            type = "QUERY";
                            code = 403;
                            result = "Forbidden\n";
                        } else {
                            long queryTime = Long.parseLong(command.substring(1));
                            if (queryTime == 0) {
                                type = "QUERY";
                                code = 200;
                                result = "";
                            } else {
                                User.Query query = userLogin.getQuerySafe(queryTime);
                                if (query == null) {
                                    type = "QUERY";
                                    code = 403;
                                    result = "Forbidden\n";
                                } else {
                                    type = "QUERY";
                                    code = 200;
//                                    parameterMap = getParameterMap(exchange);
                                    if (parameterMap != null && parameterMap.containsKey("POLICY")) {
                                        String policy = (String) parameterMap.get("POLICY");
                                        if (policy.startsWith("WHITE_")) {
                                            query.white(queryTime, policy.substring(6));
                                            query.processComplainForWhite();
                                        } else if (policy.startsWith("BLOCK_")) {
                                            query.block(queryTime, policy.substring(6));
                                            query.processComplainForBlock();
                                        }
                                    }
                                    result = getControlPanel(locale, query, queryTime);
                                }
                            }
                        }
                    } else if (isValidDomainOrIP(command.substring(1))) {
                        String title;
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            title = "Página de checagem DNSBL";
                        } else {
                            title = "DNSBL check page";
                        }
                        String ip = command.substring(1);
                        if (Subnet.isValidIP(ip)) {
//                            parameterMap = getParameterMap(exchange);
                            if (parameterMap != null && parameterMap.containsKey("identifier")) {
                                boolean valid = true;
                                if (Core.hasRecaptchaKeys()) {
                                    if (parameterMap.containsKey("recaptcha_challenge_field")
                                            && parameterMap.containsKey("recaptcha_response_field")
                                            ) {
                                        // reCAPCHA convencional.
                                        String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                        String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                        ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                        String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                        String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                        if (recaptchaResponse == null) {
                                            valid = false;
                                        } else {
                                            ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                            valid = response.isValid();
                                        }
                                    } else if (parameterMap.containsKey("g-recaptcha-response")) {
                                        // TODO: novo reCAPCHA.
                                        valid = false;
                                    } else {
                                        // reCAPCHA necessário.
                                        valid = false;
                                    }
                                }
                                if (valid) {
                                    TreeSet<String> postmaterSet = getPostmaterSet(ip);
                                    clientEmail = client == null || client.hasPermission(NONE) ? null : client.getEmail();
                                    if (clientEmail != null) {
                                        postmaterSet.add(clientEmail);
                                    }
                                    Client abuseClient = Client.getByIP(ip);
                                    String abuseEmail = abuseClient == null || abuseClient.hasPermission(NONE) ? null : abuseClient.getEmail();
                                    if (abuseEmail != null) {
                                        postmaterSet.add(abuseEmail);
                                    }
//                                    String message;
//                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                        message = "Chave de desbloqueio não pode ser enviada "
//                                                + "devido a um erro interno.";
//                                    } else {
//                                        message = "Unblocking key can not be sent "
//                                                + "due to an internal error.";
//                                    }
//                                    TreeSet<String> emailSet = (TreeSet<String>) parameterMap.get("identifier");
//                                    for (String email : emailSet) {
//                                        if (postmaterSet.contains(email)) {
//                                            String url = Core.getUnblockURL(email, ip);
//                                            try {
//                                                if (enviarDesbloqueioDNSBL(locale, url, ip, email)) {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio enviada com sucesso.";
//                                                    } else {
//                                                        message = "Unblocking key successfully sent.";
//                                                    }
//                                                }
//                                            } catch (SendFailedException ex) {
//                                                if (ex.getCause() instanceof SMTPAddressFailedException) {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio não pode ser enviada "
//                                                                + "porque o endereço " + email + " não existe.";
//                                                    } else {
//                                                        message = "Unlock key can not be sent because the "
//                                                                + "" + email + " address does not exist.";
//                                                    }
//                                                } else if (ex.getCause() == null) {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio não pode ser enviada "
//                                                                + "devido a recusa do servidor de destino.";
//                                                    } else {
//                                                        message = "Unlock key can not be sent due to "
//                                                                + "denial of destination server.";
//                                                    }
//                                                } else {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio não pode ser enviada "
//                                                                + "devido a recusa do servidor de destino:\n"
//                                                                + ex.getCause().getMessage();
//                                                    } else {
//                                                        message = "Unlock key can not be sent due to "
//                                                                + "denial of destination server:\n"
//                                                                + ex.getCause().getMessage();
//                                                    }
//                                                }
//                                            } catch (MailConnectException ex) {
//                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                    message = "Chave de desbloqueio não pode ser enviada "
//                                                            + "pois o MX de destino se encontra indisponível.";
//                                                } else {
//                                                    message = "Unlock key can not be sent because "
//                                                            + "the destination MX is unavailable.";
//                                                }
//                                            } catch (MessagingException ex) {
//                                                if (ex.getCause() instanceof SocketTimeoutException) {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio não pode ser enviada "
//                                                                + "pois o MX de destino demorou demais para "
//                                                                + "iniciar a transação SMTP.\n"
//                                                                + "Para que o envio da chave seja possível, "
//                                                                + "o inicio da transação SMTP não "
//                                                                + "pode levar mais que 30 segundos.";
//                                                    } else {
//                                                        message = "Unlock key can not be sent because the "
//                                                                + "destination MX has taken too long to "
//                                                                + "initiate the SMTP transaction.\n"
//                                                                + "For the key delivery is possible, "
//                                                                + "the beginning of the SMTP transaction "
//                                                                + "can not take more than 30 seconds.";
//                                                    }
//                                                } else {
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "Chave de desbloqueio não pode ser enviada pois "
//                                                                + "o MX de destino está recusando nossa mensagem.";
//                                                    } else {
//                                                        message = "Unlock key can not be sent because "
//                                                                + "the destination MX is declining our message.";
//                                                    }
//                                                }
//                                            }
//                                        }
//                                    }
//                                    type = "DNSBL";
//                                    code = 200;
//                                    result = getMessageHMTL(locale, title, message);
                                    
                                    
                                    
                                    type = "DNSBL";
                                    code = 200;
                                    result = null;
                                    TreeSet<String> emailSet = (TreeSet<String>) parameterMap.get("identifier");
                                    for (String email : emailSet) {
                                        if (postmaterSet.contains(email)) {
                                            String url = Core.getUnblockURL(locale, email, ip);
                                            result = getDesbloqueioHTML(locale, url, ip, email);
                                        }
                                    }
                                    if (result == null) {
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            result = getMessageHMTL(
                                                    locale, title,
                                                    "Chave de desbloqueio não pode ser enviada "
                                                            + "devido a um erro interno."
                                            );
                                        } else {
                                            result = getMessageHMTL(
                                                    locale, title,
                                                    "Unblocking key can not be sent due "
                                                            + "to an internal error."
                                            );
                                        }
                                    }
                                } else {
                                    type = "DNSBL";
                                    code = 200;
                                    String message;
                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                        message = "O desafio do reCAPTCHA não foi resolvido";
                                    } else {
                                        message = "The reCAPTCHA challenge has not been resolved";
                                    }
                                    result = getDNSBLHTML(locale, client, ip, message);
                                }
                            } else {
                                type = "DNSBL";
                                code = 200;
                                String message;
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    message = "O e-mail do responsável pelo IP não foi definido";
                                } else {
                                    message = "The e-mail of responsible IP was not set";
                                }
                                result = getDNSBLHTML(locale, client, ip, message);
                            }
                        } else {
                            type = "DNSBL";
                            code = 500;
                            String message;
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                message = "O identificador informado não é um IP válido.";
                            } else {
                                message = "Informed identifier is not a valid IP domain.";
                            }
                            result = getMessageHMTL(locale, title, message);
                        }
                    } else {
                        try {
                            String ticket = command.substring(1);
                            byte[] byteArray = Server.decryptToByteArrayURLSafe(ticket);
                            if (byteArray.length > 8) {
                                long date = byteArray[7] & 0xFF;
                                date <<= 8;
                                date += byteArray[6] & 0xFF;
                                date <<= 8;
                                date += byteArray[5] & 0xFF;
                                date <<= 8;
                                date += byteArray[4] & 0xFF;
                                date <<= 8;
                                date += byteArray[3] & 0xFF;
                                date <<= 8;
                                date += byteArray[2] & 0xFF;
                                date <<= 8;
                                date += byteArray[1] & 0xFF;
                                date <<= 8;
                                date += byteArray[0] & 0xFF;
                                if (System.currentTimeMillis() - date > 432000000) {
                                    String title;
                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                        title = "Página do SPFBL";
                                    } else {
                                        title = "SPFBL page";
                                    }
                                    type = "HTTPC";
                                    code = 500;
                                    String message;
                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                        message = "Ticket expirado.";
                                    } else {
                                        message = "Expired ticket.";
                                    }
                                    result = getMessageHMTL(locale, title, message);
                                } else {
                                    String query = Core.HUFFMAN.decode(byteArray, 8);
                                    StringTokenizer tokenizer = new StringTokenizer(query, " ");
                                    String operator = tokenizer.nextToken();
                                    if (operator.equals("spam")) {
                                        String sender = null;
                                        String recipient = null;
                                        String clientTicket = null;
                                        TreeSet<String> tokenSet = new TreeSet<String>();
                                        while (tokenizer.hasMoreTokens()) {
                                            String token = tokenizer.nextToken();
                                            if (token.startsWith(">") && Domain.isEmail(token.substring(1))) {
                                                recipient = token.substring(1);
                                            } else if (token.endsWith(":") && Domain.isEmail(token.substring(0, token.length() - 1))) {
                                                clientTicket = token.substring(0, token.length() - 1);
                                            } else if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                                                sender = token;
                                                tokenSet.add(token);
                                            } else if (Domain.isEmail(token)) {
                                                sender = token;
                                                tokenSet.add(token);
                                            } else {
                                                tokenSet.add(token);
                                            }
                                        }
                                        clientTicket = clientTicket == null ? "" : clientTicket + ':';
//                                        parameterMap = getParameterMap(exchange);
                                        if (parameterMap != null && parameterMap.containsKey("identifier")) {
                                            boolean valid = true;
                                            TreeSet<String> identifierSet = (TreeSet<String>) parameterMap.get("identifier");
                                            if (Core.hasRecaptchaKeys()) {
                                                if (parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            tokenSet = SPF.expandTokenSet(tokenSet);
                                            if (valid) {
                                                TreeSet<String> blockSet = new TreeSet<String>();
                                                for (String identifier : identifierSet) {
                                                    if (tokenSet.contains(identifier)) {
                                                        long time2 = System.currentTimeMillis();
                                                        String block = clientTicket + identifier + '>' + recipient;
                                                        if (Block.addExact(block)) {
                                                            Server.logQuery(
                                                                    time2, "BLOCK",
                                                                    origin,
                                                                    "BLOCK ADD " + block,
                                                                    "ADDED"
                                                            );
                                                        }
                                                        blockSet.add(identifier);
                                                    }
                                                }
                                                type = "HTTPC";
                                                code = 200;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    result = "Bloqueados: " + blockSet + " >" + recipient + "\n";
                                                } else {
                                                    result = "Blocked: " + blockSet + " >" + recipient + "\n";
                                                }
                                            } else {
                                                type = "HTTPC";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio do reCAPTCHA não foi resolvido.";
                                                } else {
                                                    message = "The CAPTCHA challenge has not been resolved.";
                                                }
                                                result = getComplainHMTL(locale, tokenSet, identifierSet, message, true);
                                            }
                                        } else {
                                            type = "HTTPC";
                                            code = 500;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                result = "Identificadores indefinidos.\n";
                                            } else {
                                                result = "Undefined identifiers.\n";
                                            }
                                        }
                                    } else if (operator.equals("unblock")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de desbloqueio do SPFBL";
                                        } else {
                                            title = "SPFBL unblock page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            String clientTicket = tokenizer.nextToken();
                                            String ip = tokenizer.nextToken();
                                            if (!tokenizer.hasMoreTokens()) {
                                                if (valid) {
                                                    String message;
                                                    if (Block.clearCIDR(ip, clientTicket)) {
                                                        TreeSet<String> tokenSet = Reverse.getPointerSet(ip);
                                                        tokenSet.add(clientTicket);
                                                        String block;
                                                        for (String token : tokenSet) {
                                                            while ((block = Block.find(null, null, token, false, true, true, false)) != null) {
                                                                if (Block.dropExact(block)) {
                                                                    Server.logInfo("false positive BLOCK '" + block + "' detected by '" + clientTicket + "'.");
                                                                }
                                                            }
                                                        }
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "O IP " + ip + " foi desbloqueado com sucesso.";
                                                        } else {
                                                            message = "The IP " + ip + " was successfully unblocked.";
                                                        }
                                                    } else {
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "O IP " + ip + " já estava desbloqueado.";
                                                        } else {
                                                            message = "The IP " + ip + " was already unblocked.";
                                                        }
                                                    }
                                                    type = "BLOCK";
                                                    code = 200;
                                                    result = getMessageHMTL(locale, title, message);
                                                } else {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O desafio reCAPTCHA não foi resolvido. "
                                                                + "Tente novamente.";
                                                    } else {
                                                        message = "The reCAPTCHA challenge was not resolved. "
                                                                + "Try again.";
                                                    }
                                                    result = getUnblockDNSBLHMTL(locale, message);
                                                }
                                            } else if (valid) {
                                                String sender = tokenizer.nextToken();
                                                String recipient = tokenizer.nextToken();
                                                String hostname = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
                                                clientTicket = clientTicket == null ? "" : clientTicket + ':';
                                                String mx = Domain.extractHost(sender, true);
                                                String origem = Provider.containsExact(mx) ? sender : mx;
                                                String white = origem + ">" + recipient;
                                                String url = Core.getWhiteURL(locale, white, clientTicket, ip, sender, hostname, recipient);
                                                String message;
                                                try {
                                                    if (enviarDesbloqueio(url, sender, recipient)) {
                                                        white = White.normalizeTokenWhite(white);
                                                        Block.addExact(clientTicket + white);
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "A solicitação de desbloqueio foi enviada "
                                                                    + "para o destinatário " + recipient + ".\n"
                                                                    + "A fim de não prejudicar sua reputação, "
                                                                    + "aguarde pelo desbloqueio sem enviar novas mensagens."
                                                                    + (NoReply.contains(sender, true) ? "" : "\n"
                                                                    + "Você receberá uma mensagem deste sistema assim "
                                                                    + "que o destinatário autorizar o recebimento.");
                                                        } else {
                                                            message = "The release request was sent to the "
                                                                    + "recipient " + recipient + ".\n"
                                                                    + "In order not to damage your reputation, "
                                                                    + "wait for the release without sending new messages."
                                                                    + (NoReply.contains(sender, true) ? "" : "\n"
                                                                    + "You will receive a message from this system "
                                                                    + "when the recipient authorize receipt.");
                                                        }
                                                    } else {
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "Não foi possível enviar a solicitação de "
                                                                    + "desbloqueio para o destinatário "
                                                                    + "" + recipient + " devido a problemas técnicos.";
                                                        } else {
                                                            message = "Could not send the request release to the recipient "
                                                                    + "" + recipient + " due to technical problems.";
                                                        }
                                                    }
                                                } catch (SendFailedException ex) {
                                                    if (ex.getCause() instanceof SMTPAddressFailedException) {
                                                        if (ex.getCause().getMessage().contains(" 5.1.1 ")) {
                                                            Trap.addInexistentSafe(user, recipient);
                                                        }
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "Chave de desbloqueio não pode ser enviada "
                                                                    + "porque o endereço " + recipient + " não existe.";
                                                        } else {
                                                            message = "Unlock key can not be sent because the "
                                                                    + "" + recipient + " address does not exist.";
                                                        }
                                                    } else {
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "A solicitação de desbloqueio não pode ser enviada "
                                                                    + "devido a recusa do servidor de destino:\n"
                                                                    + ex.getCause().getMessage();
                                                        } else {
                                                            message = "The release request can not be sent due to "
                                                                    + "denial of destination server:\n"
                                                                    + ex.getCause().getMessage();
                                                        }
                                                    }
                                                } catch (MailConnectException ex) {
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "A solicitação de desbloqueio não pode ser enviada "
                                                                + "pois o MX de destino se encontra indisponível.";
                                                    } else {
                                                        message = "The release request can not be sent because "
                                                                + "the destination MX is unavailable.";
                                                    }
                                                } catch (MessagingException ex) {
                                                    if (ex.getCause() instanceof SocketTimeoutException) {
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "A solicitação de desbloqueio não pode ser enviada pois "
                                                                    + "o MX de destino demorou demais para iniciar a transação SMTP.";
                                                        } else {
                                                            message = "The release request can not be sent because the destination "
                                                                    + "MX has taken too long to initiate the SMTP transaction.";
                                                        }
                                                    } else {
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "A solicitação de desbloqueio não pode ser enviada pois "
                                                                    + "o MX de destino está recusando nossa mensagem.";
                                                        } else {
                                                            message = "The release request can not be sent because "
                                                                    + "the destination MX is declining our message.";
                                                        }
                                                    }
                                                }
                                                type = "BLOCK";
                                                code = 200;
                                                result = getMessageHMTL(locale, title, message);
                                            } else {
                                                type = "BLOCK";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio reCAPTCHA não foi resolvido. "
                                                            + "Tente novamente.";
                                                } else {
                                                    message = "The reCAPTCHA challenge was not resolved. "
                                                            + "Try again.";
                                                }
                                                result = getUnblockHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "SPFSP";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("holding")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String email = tokenizer.nextToken();
                                                User userLocal = User.get(email);
                                                Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                                if (queryLocal == null) {
                                                    type = "HOLDN";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Este ticket de liberação não existe mais.";
                                                    } else {
                                                        message = "This release ticket does not exist any more.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isResult("WHITE")) {
                                                    type = "HOLDN";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi entregue.";
                                                    } else {
                                                        message = "This message has already been delivered.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isWhiteSender()) {
                                                    type = "HOLDN";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi liberada.";
                                                    } else {
                                                        message = "This message has already been released.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isBlockSender()) {
                                                    type = "HOLDN";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem foi definitivamente bloqueada.";
                                                    } else {
                                                        message = "This message has been permanently blocked.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isRecipientAdvised()) {
                                                    type = "HOLDN";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O destinatário ainda não decidiu pela liberação desta mensagem.";
                                                    } else {
                                                        message = "The recipient has not yet decided to release this message.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.adviseRecipientHOLD(date)) {
                                                    type = "HOLDN";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Solicitação foi enviada com sucesso.";
                                                    } else {
                                                        message = "Request was sent successfully.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else {
                                                    type = "HOLDN";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "A solicitação não pode ser envada por falha de sistema.";
                                                    } else {
                                                        message = "The request can not be committed due to system failure.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            } else {
                                                type = "HOLDN";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio reCAPTCHA não foi "
                                                            + "resolvido. Tente novamente.";
                                                } else {
                                                    message = "The reCAPTCHA challenge "
                                                            + "was not resolved. Try again.";
                                                }
                                                result = getRequestHoldHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "HOLDN";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("unhold")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String email = tokenizer.nextToken();
                                                User userLocal = User.get(email);
                                                Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                                if (queryLocal == null) {
                                                    type = "UHOLD";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Este ticket de liberação não existe mais.";
                                                    } else {
                                                        message = "This release ticket does not exist any more.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isDelivered()) {
                                                    type = "UHOLD";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi entregue.";
                                                    } else {
                                                        message = "This message has already been delivered.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (!queryLocal.isHolding()) {
                                                    type = "UHOLD";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem foi descartada antes que pudesse ser liberada.";
                                                    } else {
                                                        message = "This message was discarded before it could be released.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isWhiteSender()) {
                                                    type = "UHOLD";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi liberada e será entregue em breve.";
                                                    } else {
                                                        message = "This message has already been released and will be delivered shortly.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.whiteSender(date)) {
                                                    type = "UHOLD";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "A mensagem foi liberada com sucesso e será entregue em breve.";
                                                    } else {
                                                        message = "The message has been successfully released and will be delivered shortly.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else {
                                                    type = "UHOLD";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "A liberação não pode ser efetivada por falha de sistema.";
                                                    } else {
                                                        message = "The release can not be effected due to system failure.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            } else {
                                                type = "UHOLD";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio reCAPTCHA não foi "
                                                            + "resolvido. Tente novamente.";
                                                } else {
                                                    message = "The reCAPTCHA challenge "
                                                            + "was not resolved. Try again.";
                                                }
                                                result = getReleaseHoldHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "UHOLD";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("block")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de bloqueio do SPFBL";
                                        } else {
                                            title = "SPFBL block page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String email = tokenizer.nextToken();
                                                User userLocal = User.get(email);
                                                Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                                if (queryLocal == null) {
                                                    type = "BLOCK";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Este ticket de bloqueio não existe mais.";
                                                    } else {
                                                        message = "This block ticket does not exist any more.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isResult("ACCEPT") && queryLocal.isWhiteSender()) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta remetente foi liberado por outro usuário.";
                                                    } else {
                                                        message = "This sender has been released by another user.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isResult("ACCEPT") && queryLocal.isBlockSender()) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta remetente já foi bloqueado.";
                                                    } else {
                                                        message = "This message has already been discarded.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isResult("ACCEPT") && queryLocal.blockSender(date)) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O remetente foi bloqueado com sucesso.";
                                                    } else {
                                                        message = "The sender was successfully blocked.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isResult("BLOCK") || queryLocal.isResult("REJECT")) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi descartada.";
                                                    } else {
                                                        message = "This message has already been discarded.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isWhiteSender()) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem foi liberada por outro usuário.";
                                                    } else {
                                                        message = "This message has been released by another user.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.isBlockSender() || queryLocal.isAnyLinkBLOCK()) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Esta mensagem já foi bloqueada e será descartada em breve.";
                                                    } else {
                                                        message = "This message has already been blocked and will be discarded soon.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else if (queryLocal.blockSender(date)) {
                                                    type = "BLOCK";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "A mensagem foi bloqueada com sucesso e será descartada em breve.";
                                                    } else {
                                                        message = "The message has been successfully blocked and will be discarded soon.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else {
                                                    type = "BLOCK";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O bloqueio não pode ser efetivado por falha de sistema.";
                                                    } else {
                                                        message = "The block can not be effected due to system failure.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            } else {
                                                type = "BLOCK";
                                                code = 200;
                                                String message;
                                                String text;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "A mensagem retida por suspeita de SPAM";
                                                    text = "O desafio reCAPTCHA não foi resolvido. Tente novamente.";
                                                } else {
                                                    message = "The message retained on suspicion of SPAM";
                                                    text = "The reCAPTCHA challenge was not resolved. Try again.";
                                                }
                                                result = getBlockHMTL(locale, message, text);
                                            }
                                        } catch (Exception ex) {
                                            type = "BLOCK";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("unsubscribe")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de cancelamento do SPFBL";
                                        } else {
                                            title = "SPFBL unsubscribe page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String email = tokenizer.nextToken();
                                                if (NoReply.add(email)) {
                                                    type = "CANCE";
                                                    code = 200;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O envio de alertas foi cancelado para " + email + " com sucesso.";
                                                    } else {
                                                        message = "Alert sending has been canceled for " + email + " successfully.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                } else {
                                                    type = "CANCE";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O sistema de alerta já estava cancelado para " + email + ".";
                                                    } else {
                                                        message = "The warning system was already unsubscribed for " + email + ".";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            } else {
                                                type = "CANCE";
                                                code = 200;
                                                String message;
                                                String text;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Cancelamento de alertas do SPFBL";
                                                    text = "O desafio reCAPTCHA não foi resolvido. Tente novamente.";
                                                } else {
                                                    message = "Unsubscribing SPFBL alerts.";
                                                    text = "The reCAPTCHA challenge was not resolved. Try again.";
                                                }
                                                result = getUnsubscribeHMTL(locale, message, text);
                                            }
                                        } catch (Exception ex) {
                                            type = "CANCE";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("release")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        try {

                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String id = tokenizer.nextToken();
                                                String message;
                                                if (Defer.release(id)) {
                                                    String clientTicket = SPF.getClient(ticket);
                                                    String sender = SPF.getSender(ticket);
                                                    String recipient = SPF.getRecipient(ticket);
                                                    if (clientTicket != null && sender != null && recipient != null) {
                                                        if (White.addExact(clientTicket + ":" + sender + ";PASS>" + recipient)) {
                                                            Server.logDebug("WHITE ADD " + clientTicket + ":" + sender + ";PASS>" + recipient);
                                                        }
                                                    }
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Sua mensagem foi liberada com sucesso.";
                                                    } else {
                                                        message = "Your message has been successfully released.";
                                                    }
                                                } else {
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "Sua mensagem já havia sido liberada.";
                                                    } else {
                                                        message = "Your message had already been released.";
                                                    }
                                                }
                                                type = "DEFER";
                                                code = 200;
                                                result = getMessageHMTL(locale, title, message);
                                            } else {
                                                type = "DEFER";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio reCAPTCHA não foi "
                                                            + "resolvido. Tente novamente.";
                                                } else {
                                                    message = "The reCAPTCHA challenge "
                                                            + "was not resolved. Try again.";
                                                }
                                                result = getReleaseHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "SPFSP";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("white")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de desbloqueio de remetente";
                                        } else {
                                            title = "Sender unblock page";
                                        }
                                        try {
                                            boolean valid = true;
                                            if (Core.hasRecaptchaKeys()) {
//                                                parameterMap = getParameterMap(exchange);
                                                if (parameterMap != null
                                                        && parameterMap.containsKey("recaptcha_challenge_field")
                                                        && parameterMap.containsKey("recaptcha_response_field")
                                                        ) {
                                                    // reCAPCHA convencional.
                                                    String recaptchaPublicKey = Core.getRecaptchaKeySite();
                                                    String recaptchaPrivateKey = Core.getRecaptchaKeySecret();
                                                    ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaPublicKey, recaptchaPrivateKey, true);
                                                    String recaptchaChallenge = (String) parameterMap.get("recaptcha_challenge_field");
                                                    String recaptchaResponse = (String) parameterMap.get("recaptcha_response_field");
                                                    if (recaptchaResponse == null) {
                                                        valid = false;
                                                    } else {
                                                        ReCaptchaResponse response = captcha.checkAnswer(remoteAddress, recaptchaChallenge, recaptchaResponse);
                                                        valid = response.isValid();
                                                    }
                                                } else if (parameterMap != null && parameterMap.containsKey("g-recaptcha-response")) {
                                                    // TODO: novo reCAPCHA.
                                                    valid = false;
                                                } else {
                                                    // reCAPCHA necessário.
                                                    valid = false;
                                                }
                                            }
                                            if (valid) {
                                                String white = White.normalizeTokenWhite(tokenizer.nextToken());
                                                String clientTicket = tokenizer.nextToken();
                                                white = clientTicket + white;
                                                String userEmail = clientTicket.replace(":", "");
                                                String ip = tokenizer.nextToken();
                                                String sender = tokenizer.nextToken();
                                                String recipient = tokenizer.nextToken();
                                                String hostname = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
                                                if (sentUnblockConfirmationSMTP.containsKey(command.substring(1))) {
                                                    type = "WHITE";
                                                    code = 200;
                                                    result = enviarConfirmacaoDesbloqueio(
                                                            command.substring(1),
                                                            recipient, sender, locale
                                                    );
                                                } else if (White.addExact(white)) {
                                                    Block.clear(userEmail, ip, sender, hostname, "PASS", recipient);
//                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                        message = "O desbloqueio do remetente '" + sender + "' foi efetuado com sucesso.";
//                                                    } else {
//                                                        message = "The unblock of sender '" + sender + "' has been successfully performed.";
//                                                    }
//                                                    if (!enviarConfirmacaoDesbloqueio(recipient, sender, locale)) {
//                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
//                                                            message += "\nPor favor, informe ao remetente sobre o desbloqueio.";
//                                                        } else {
//                                                            message += "\nPlease inform the sender about the release.";
//                                                        }
//                                                    }
                                                    type = "WHITE";
                                                    code = 200;
                                                    result = enviarConfirmacaoDesbloqueio(
                                                            command.substring(1),
                                                            recipient, sender, locale
                                                    );
                                                } else {
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O desbloqueio do remetente '" + sender + "' já havia sido efetuado.";
                                                    } else {
                                                        message = "The unblock of sender '" + sender + "' had been made.";
                                                    }
                                                    type = "WHITE";
                                                    code = 200;
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            } else {
                                                type = "WHITE";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "O desafio reCAPTCHA não foi resolvido. "
                                                            + "Tente novamente.";
                                                } else {
                                                    message = "The reCAPTCHA challenge was not resolved. "
                                                            + "Try again.";
                                                }
                                                result = getWhiteHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "SPFSP";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else {
                                        type = "HTTPC";
                                        code = 403;
                                        result = "Forbidden\n";
                                    }
                                }
                            } else {
                                type = "HTTPC";
                                code = 403;
                                result = "Forbidden\n";
                            }
                        } catch (Exception ex) {
                            type = "HTTPC";
                            code = 403;
                            result = "Forbidden\n";
                        }
                    }
                } else if (request.equals("GET")) {
                    if (command.equals("/")) {
                        type = "MMENU";
                        code = 200;
                        String message;
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            message = "Página principal do serviço SPFBL";
                        } else {
                            message = "This is SPFBL's main page";
                        }
                        result = getMainHTML(locale, message, remoteAddress);
                    } else if (command.startsWith("/favicon.ico")) {
                        type = "HTTPC";
                        code = 403;
                        result = "Forbidden\n";
                    } else if (command.startsWith("/robots.txt")) {
                        type = "HTTPC";
                        code = 403;
                        result = "Forbidden\n";
                    } else if (Domain.isEmail(command.substring(1))) {
                        String message;
                        String userEmail = command.substring(1).toLowerCase();
                        User userLogin = getUser(exchange);
                        if (userLogin != null && userLogin.isEmail(userEmail)) {
//                            parameterMap = getParameterMap(exchange);
                            Long begin = (Long) (parameterMap == null ? null : parameterMap.get("begin"));
                            String filter = (String) (parameterMap == null ? null : parameterMap.get("filter"));
                            message = getControlPanel(locale, userLogin, begin, filter);
                        } else if ((userLogin = User.get(userEmail)) == null) {
                            message = getMessageHMTL(
                                    locale,
                                    "Login do SPFBL",
                                    "Usuário inexistente."
                            );
                        } else if (userLogin.tooManyFails()) {
                            long failTime = userLogin.getFailTime();
                            int pageTime = (int) (failTime / 1000) + 1;
                            String tempoPunicao = getTempoPunicao(failTime);
                            message = getRedirectHMTL(
                                    locale,
                                    "Login do SPFBL",
                                    "Conta temporariamente bloqueada por excesso de logins fracassados.\n"
                                    + "Aguarde cerca de " + tempoPunicao + " para tentar novamente.",
                                    command,
                                    pageTime
                            );
                        } else if (userLogin.hasTransitionOTP()) {
                            if (userLogin.hasSecretOTP()) {
                                message = getLoginOTPHMTL(
                                        locale,
                                        "Página de login do SPFBL",
                                        "Para confirmar a mudança de segredo "
                                        + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a>,\n"
                                        + "digite o valor da nova chave enviada por e-mail:"
                                );
                            } else {
                                message = getLoginOTPHMTL(
                                        locale,
                                        "Página de login do SPFBL",
                                        "Para ativar a senha "
                                        + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                        + "da sua conta, digite o valor da chave enviada por e-mail:"
                                );
                            }
                        } else if (userLogin.hasSecretOTP()) {
                            message = getLoginOTPHMTL(
                                    locale,
                                    "Página de login do SPFBL",
                                    "Para entrar no painel de controle, digite o valor da chave "
                                    + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                    + "de sua conta:"
                            );
                        } else {
                            message = getSendOTPHMTL(
                                    locale,
                                    "Seu e-mail ainda não possui senha "
                                    + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                    + "neste sistema",
                                    "Para receber a chave "
                                    + "<a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> "
                                    + "em seu e-mail, resolva o reCAPTCHA abaixo."
                            );
                        }
                        type = "PANEL";
                        code = 200;
                        result = message;
                    } else if (Core.isLong(command.substring(1))) {
                        User userLogin = getUser(exchange);
                        if (userLogin == null) {
                            type = "QUERY";
                            code = 403;
                            result = "Forbidden\n";
                        } else {
                            long queryTime = Long.parseLong(command.substring(1));
                            if (queryTime == 0) {
                                type = "QUERY";
                                code = 200;
                                result = "";
                            } else {
                                User.Query query = userLogin.getQuerySafe(queryTime);
                                if (query == null) {
                                    type = "QUERY";
                                    code = 403;
                                    result = "Forbidden\n";
                                } else {
                                    type = "QUERY";
                                    code = 200;
                                    result = getControlPanel(locale, query, queryTime);
                                }
                            }
                        }
                    } else if ((file = getWebFile(command.substring(1))) != null) {
                        exchange.sendResponseHeaders(200, file.length());
                        OutputStream outputStream = exchange.getResponseBody();
                        try {
                            Files.copy(file.toPath(), outputStream);
                            result = file.getName() + "\n";
                        } catch (Exception ex) {
                            Server.logError(ex);
                            result = "FILE READ ERROR\n";
                        } finally {
                            outputStream.close();
                            type = "HTTPF";
                            code = 0;
                        }
                    } else if (command.startsWith("/dnsbl/")) {
                        type = "DNSBL";
                        code = 200;
                        String query = command.substring(7);
                        String url = Core.getURL(locale, query);
                        result = getRedirectHTML(locale, url);
                    } else if (isValidDomainOrIP(command.substring(1))) {
                        String title;
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            title = "Página de checagem DNSBL";
                        } else {
                            title = "DNSBL check page";
                        }
                        String query = command.substring(1);
                        if (Subnet.isValidIP(query)) {
                            String ip = Subnet.normalizeIP(query);
                            if (sentUnblockKeySMTP.containsKey(ip)) {
                                type = "DNSBL";
                                code = 200;
                                String email = null;
                                String url = null;
                                result = getDesbloqueioHTML(locale, url, ip, email);
                            } else {
                                type = "DNSBL";
                                code = 200;
                                String message;
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    message = "Resultado da checagem do IP " + ip;
                                } else {
                                    message = "Check result of IP " + ip;
                                }
                                result = getDNSBLHTML(locale, client, ip, message);
                            }
                        } else if (Domain.isHostname(query)) {
                            String hostname = Domain.normalizeHostname(query, false);
                            type = "DNSBL";
                            code = 200;
                            String message;
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                message = "Resultado da checagem do domínio '" + hostname + "'";
                            } else {
                                message = "Check the result of domain " + hostname;
                            }
                            result = getDNSBLHTML(locale, client, hostname, message);
                        } else {
                            type = "DNSBL";
                            code = 500;
                            String message;
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                message = "O identificador informado não é um IP nem um domínio válido.";
                            } else {
                                message = "Informed identifier is not a valid IP or a valid domain.";
                            }
                            result = getMessageHMTL(locale, title, message);
                        }
                    } else {
                        try {
                            String ticket = command.substring(1);
                            byte[] byteArray = Server.decryptToByteArrayURLSafe(ticket);
                            if (byteArray.length > 8) {
                                long date = byteArray[7] & 0xFF;
                                date <<= 8;
                                date += byteArray[6] & 0xFF;
                                date <<= 8;
                                date += byteArray[5] & 0xFF;
                                date <<= 8;
                                date += byteArray[4] & 0xFF;
                                date <<= 8;
                                date += byteArray[3] & 0xFF;
                                date <<= 8;
                                date += byteArray[2] & 0xFF;
                                date <<= 8;
                                date += byteArray[1] & 0xFF;
                                date <<= 8;
                                date += byteArray[0] & 0xFF;
                                if (System.currentTimeMillis() - date > 432000000) {
                                    String title;
                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                        title = "Página do SPFBL";
                                    } else {
                                        title = "SPFBL page";
                                    }
                                    type = "HTTPC";
                                    code = 500;
                                    String message;
                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                        message = "Ticket expirado.";
                                    } else {
                                        message = "Expired ticket.";
                                    }
                                    result = getMessageHMTL(locale, title, message);
                                } else {
                                    String query = Core.HUFFMAN.decode(byteArray, 8);
                                    StringTokenizer tokenizer = new StringTokenizer(query, " ");
                                    String operator = tokenizer.nextToken();
                                    if (operator.equals("spam")) {
                                        try {
                                            String sender = null;
                                            String recipient = null;
                                            String clientTicket = null;
                                            TreeSet<String> tokenSet = new TreeSet<String>();
                                            while (tokenizer.hasMoreTokens()) {
                                                String token = tokenizer.nextToken();
                                                if (token.startsWith(">") && Domain.isEmail(token.substring(1))) {
                                                    recipient = token.substring(1);
                                                } else if (token.endsWith(":") && Domain.isEmail(token.substring(0, token.length() - 1))) {
                                                    clientTicket = token.substring(0, token.length() - 1);
                                                } else if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                                                    sender = token;
                                                    tokenSet.add(token);
                                                } else if (Domain.isEmail(token)) {
                                                    sender = token;
                                                    tokenSet.add(token);
                                                } else {
                                                    tokenSet.add(token);
                                                }
                                            }
                                            boolean whiteBlockForm = recipient != null;
                                            TreeSet<String> complainSet = SPF.addComplain(origin, date, tokenSet, recipient);
                                            tokenSet = SPF.expandTokenSet(tokenSet);
                                            TreeSet<String> selectionSet = new TreeSet<String>();
                                            String message;
                                            if (complainSet == null) {
                                                complainSet = SPF.getComplain(ticket);
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "A mensagem já havia sido denunciada antes.";
                                                } else {
                                                    message = "The message had been reported before.";
                                                }
                                            } else {
                                                if (clientTicket != null && sender != null && recipient != null) {
                                                    if (White.dropExact(clientTicket + ":" + sender + ";PASS>" + recipient)) {
                                                        Server.logDebug("WHITE DROP " + clientTicket + ":" + sender + ";PASS>" + recipient);
                                                    }
                                                }
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "A mensagem foi denunciada com sucesso.";
                                                } else {
                                                    message = "The message has been reported as SPAM.";
                                                }
                                            }
                                            for (String token : complainSet) {
                                                if (!Subnet.isValidIP(token)) {
                                                    selectionSet.add(token);
                                                }
                                            }
                                            type = "SPFSP";
                                            code = 200;
                                            result = getComplainHMTL(locale, tokenSet, selectionSet, message, whiteBlockForm);
                                        } catch (Exception ex) {
                                            type = "SPFSP";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("unblock")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de desbloqueio do SPFBL";
                                        } else {
                                            title = "SPFBL unblock page";
                                        }
                                        try {
                                            String clientTicket = tokenizer.nextToken();
                                            String ip = tokenizer.nextToken();
                                            if (!tokenizer.hasMoreTokens()) {
                                                type = "BLOCK";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Para desbloquear o IP '" + ip + "', "
                                                            + "resolva o desafio reCAPTCHA abaixo.";
                                                } else {
                                                    message = "To unblock the IP '" + ip + "', "
                                                            + "solve the CAPTCHA below.";
                                                }
                                                result = getUnblockDNSBLHMTL(locale, message);
                                            } else {
                                                String sender = tokenizer.nextToken();
                                                String recipient = tokenizer.nextToken();
                                                String hostname = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
                                                String mx = Domain.extractHost(sender, true);
                                                SPF.Qualifier qualifier = SPF.getQualifier2(ip, sender, hostname, true);
                                                if (qualifier == SPF.Qualifier.PASS) {
                                                    clientTicket = clientTicket == null ? "" : clientTicket + ':';
                                                    String origem = Provider.containsExact(mx) ? sender : mx;
                                                    if (sender == null || recipient == null) {
                                                        type = "BLOCK";
                                                        code = 500;
                                                        String message;
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "Este ticket de desbloqueio não "
                                                                    + "contém remetente e destinatário.";
                                                        } else {
                                                            message = "This release ticket does not "
                                                                    + "contains the sender and recipient.";
                                                        }
                                                        result = getMessageHMTL(locale, title, message);
                                                    } else if (White.containsExact(clientTicket + origem + ";PASS>" + recipient)) {
                                                        type = "BLOCK";
                                                        code = 200;
                                                        String message;
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "O destinatário '" + recipient + "' "
                                                                    + "já autorizou o recebimento de mensagens "
                                                                    + "do remetente '" + sender + "'.";
                                                        } else {
                                                            message = "The recipient '" + recipient + "' "
                                                                    + "already authorized receiving messages "
                                                                    + "from sender '" + sender + "'.";
                                                        }
                                                        result = getMessageHMTL(locale, title, message);
                                                    } else if (Block.containsExact(clientTicket + origem + ";PASS>" + recipient)) {
                                                        type = "BLOCK";
                                                        code = 200;
                                                        String message;
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message =  "O destinatário '" + recipient + "' "
                                                                    + "não decidiu se quer receber mensagens "
                                                                    + "do remetente '" + sender + "'.\n"
                                                                    + "Para que a reputação deste remetente "
                                                                    + "não seja prejudicada neste sistema, "
                                                                    + "é necessário que ele pare de tentar "
                                                                    + "enviar mensagens para este "
                                                                    + "destinatário até a sua decisão.\n"
                                                                    + "Cada tentativa de envio por ele, "
                                                                    + "conta um ponto negativo na "
                                                                    + "reputação dele neste sistema.";
                                                        } else {
                                                            message =  "The recipient '" + recipient + "' "
                                                                    + "not decided whether to receive messages "
                                                                    + "from sender '" + sender + "'.\n"
                                                                    + "For the reputation of the sender "
                                                                    + "is not impaired in this system, "
                                                                    + "it needs to stop trying to "
                                                                    + "send messages to this "
                                                                    + "recipient until its decision.\n"
                                                                    + "Each attempt to send him, "
                                                                    + "has a negative point in "
                                                                    + "reputation in this system.";
                                                        }
                                                        result = getMessageHMTL(locale, title, message);
                                                    } else {
                                                        type = "BLOCK";
                                                        code = 200;
                                                        String message;
                                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                            message = "Para solicitar desbloqueio do envio feito do remetente " + sender + " "
                                                                    + "para o destinatário " + recipient + ", "
                                                                    + "favor preencher o captcha abaixo.";
                                                        } else {
                                                            message = "To request unblocking from the sender " + sender + " "
                                                                    + "to the recipient " + recipient + ", "
                                                                    + "solve the challenge reCAPTCHA below.";
                                                        }
                                                        result = getUnblockHMTL(locale, message);
                                                    }
                                                } else {
                                                    type = "BLOCK";
                                                    code = 500;
                                                    String message;
                                                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                        message = "O IP " + ip + " não está autorizada no registro SPF do domínio " + mx + ".\n"
                                                                + "Para que seja possível solicitar o desbloqueio ao destinário, por meio deste sistema, "
                                                                + "configure o SPF deste domínio de modo que o envio por meio do mesmo IP resulte em PASS.\n"
                                                                + "Após fazer esta modificação, aguarde algumas horas pela propagação DNS, "
                                                                + "e volte a acessar esta mesma página para prosseguir com o processo de desbloqueio.";
                                                    } else {
                                                        message = "The IP " + ip + " is not authorized in the SPF record of domain " + mx + ".\n"
                                                                + "To be able to request unblocking to recipient, through this system, "
                                                                + "set the SPF record of this domain so that sending through the same IP results in PASS.\n"
                                                                + "After making this change, wait a few hours for DNS propagation, "
                                                                + "and re-access the same page to proceed with the unblock process.";
                                                    }
                                                    result = getMessageHMTL(locale, title, message);
                                                }
                                            }
                                        } catch (Exception ex) {
                                            Server.logError(ex);
                                            type = "BLOCK";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Ocorreu um erro no processamento desta solicitação: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            } else {
                                                message = "There was an error processing this request: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        }
                                    } else if (operator.equals("holding")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        String email = tokenizer.nextToken();
                                        User userLocal = User.get(email);
                                        Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                        if (queryLocal == null) {
                                            type = "HOLDN";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Este ticket de liberação não existe mais.";
                                            } else {
                                                message = "This release ticket does not exist any more.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isResult("WHITE")) {
                                            type = "HOLDN";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi entregue.";
                                            } else {
                                                message = "This message has already been delivered.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isWhiteSender()) {
                                            type = "HOLDN";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi liberada.";
                                            } else {
                                                message = "This message has already been released.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isBlockSender()) {
                                            type = "HOLDN";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem foi definitivamente bloqueada.";
                                            } else {
                                                message = "This message has been permanently blocked.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isRecipientAdvised()) {
                                            type = "HOLDN";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "O destinatário ainda não decidiu pela liberação desta mensagem.";
                                            } else {
                                                message = "The recipient has not yet decided to release this message.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else {
                                            type = "HOLDN";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Para solicitar liberação desta mensagem, "
                                                        + "resolva o CAPTCHA abaixo.";
                                            } else {
                                                message = "To request release of this message, "
                                                    + "solve the CAPTCHA below.";
                                            }
                                            result = getRequestHoldHMTL(locale, message);
                                        }
                                    } else if (operator.equals("unhold")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        String email = tokenizer.nextToken();
                                        User userLocal = User.get(email);
                                        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
                                        GregorianCalendar calendar = new GregorianCalendar();
                                        calendar.setTimeInMillis(date);
                                        Server.logTrace(dateFormat.format(calendar.getTime()));
                                        Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                        if (queryLocal == null) {
                                            type = "UHOLD";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Este ticket de liberação não existe mais.";
                                            } else {
                                                message = "This release ticket does not exist any more.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isDelivered()) {
                                            type = "UHOLD";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi entregue.";
                                            } else {
                                                message = "This message has already been delivered.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (!queryLocal.isHolding()) {
                                            type = "UHOLD";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem foi descartada antes que pudesse ser liberada.";
                                            } else {
                                                message = "This message was discarded before it could be released.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isWhiteSender()) {
                                            type = "UHOLD";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi liberada e será entregue em breve.";
                                            } else {
                                                message = "This message has already been released and will be delivered shortly.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else {
                                            type = "UHOLD";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Para confirmar a liberação desta mensagem, "
                                                        + "resolva o CAPTCHA abaixo.";
                                            } else {
                                                message = "To confirm the release of this message, "
                                                    + "solve the CAPTCHA below.";
                                            }
                                            result = getReleaseHoldHMTL(locale, message);
                                        }
                                    } else if (operator.equals("block")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de bloqueio do SPFBL";
                                        } else {
                                            title = "SPFBL block page";
                                        }
                                        String email = tokenizer.nextToken();
                                        User userLocal = User.get(email);
                                        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
                                        GregorianCalendar calendar = new GregorianCalendar();
                                        calendar.setTimeInMillis(date);
                                        Server.logTrace(dateFormat.format(calendar.getTime()));
                                        Query queryLocal = userLocal == null ? null : userLocal.getQuerySafe(date);
                                        if (queryLocal == null) {
                                            type = "BLOCK";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Este ticket de liberação não existe mais.";
                                            } else {
                                                message = "This release ticket does not exist any more.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isResult("ACCEPT") && queryLocal.isWhiteSender()) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta remetente foi liberado por outro usuário.";
                                            } else {
                                                message = "This sender has been released by another user.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isResult("ACCEPT") && queryLocal.isBlockSender()) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta remetente já foi bloqueado.";
                                            } else {
                                                message = "This message has already been discarded.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isResult("ACCEPT")) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            String text;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Mensagem com forte suspeita de SPAM";
                                                text = "Para confirmar o bloqueio deste remetente, resolva o CAPTCHA abaixo.";
                                            } else {
                                                message = "Message with strong suspicion of SPAM";
                                                text = "To confirm the block of this sender, solve the CAPTCHA below.";
                                            }
                                            result = getBlockHMTL(locale, message, text);
                                        } else if (queryLocal.isResult("BLOCK") || queryLocal.isResult("REJECT")) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi descartada.";
                                            } else {
                                                message = "This message has already been discarded.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isWhiteSender()) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem foi liberada por outro usuário.";
                                            } else {
                                                message = "This message has been released by another user.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else if (queryLocal.isBlockSender() || queryLocal.isAnyLinkBLOCK()) {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Esta mensagem já foi bloqueada e será descartada em breve.";
                                            } else {
                                                message = "This message has already been blocked and will be discarded soon.";
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        } else {
                                            type = "BLOCK";
                                            code = 200;
                                            String message;
                                            String text;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "A mensagem foi retida por suspeita de SPAM";
                                                text = "Para confirmar o bloqueio deste remetente, resolva o CAPTCHA abaixo.";
                                            } else {
                                                message = "The message was retained on suspicion of SPAM";
                                                text = "To confirm the block of this sender, solve the CAPTCHA below.";
                                            }
                                            result = getBlockHMTL(locale, message, text);
                                        }
                                    } else if (operator.equals("unsubscribe")) {
                                        try {
                                            String email = tokenizer.nextToken();
                                            type = "CANCE";
                                            code = 200;
                                            String message;
                                            String text;
                                            if (NoReply.contains(email, false)) {
                                                String title;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    title = "Página de cancelamento do SPFBL";
                                                    message = "O sistema de alerta já estava cancelado para " + email + ".";
                                                } else {
                                                    title = "SPFBL unsubscribe page";
                                                    message = "The warning system was already unsubscribed for " + email + ".";
                                                }
                                                result = getMessageHMTL(locale, title, message);
                                            } else {
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Cancelamento de alertas do SPFBL";
                                                    text = "O cancelamento de alertas pode prejudicar a interação com este sistema.\n"
                                                            + "Se tiver certeza que quer cancelar estes alertas para " + email + ", resolva o reCAPTCHA:";
                                                } else {
                                                    message = "Unsubscribing SPFBL alerts.";
                                                    text = "Canceling alerts can impair interaction with this system.\n"
                                                            + "If you are sure you want to cancel these alerts for " + email + ", resolve reCAPTCHA:";
                                                }
                                                result = getUnsubscribeHMTL(locale, message, text);
                                            }
                                        } catch (Exception ex) {
                                            type = "CANCE";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else if (operator.equals("release")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de liberação do SPFBL";
                                        } else {
                                            title = "SPFBL release page";
                                        }
                                        try {
                                            String id = tokenizer.nextToken();
                                            Defer defer = Defer.getDefer(date, id);
                                            if (defer == null) {
                                                type = "DEFER";
                                                code = 500;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Este ticket de liberação não existe ou já foi liberado antes.";
                                                } else {
                                                    message = "This release ticket does not exist or has been released before.";
                                                }
                                                result = getMessageHMTL(locale, title, message);
                                            } else if (defer.isReleased()) {
                                                type = "DEFER";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Sua mensagem já havia sido liberada.";
                                                } else {
                                                    message = "Your message had already been freed.";
                                                }
                                                result = getMessageHMTL(locale, title, message);
                                            } else {
                                                type = "DEFER";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Para liberar o recebimento da mensagem, "
                                                        + "resolva o desafio reCAPTCHA abaixo.";
                                                } else {
                                                    message = "To release the receipt of the message, "
                                                        + "solve the CAPTCHA below.";
                                                }
                                                result = getReleaseHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "DEFER";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Ocorreu um erro no processamento desta solicitação: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            } else {
                                                message = "There was an error processing this request: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        }
                                    } else if (operator.equals("white")) {
                                        String title;
                                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                                            title = "Página de desbloqueio do SPFBL";
                                        } else {
                                            title = "SPFBL unblock page";
                                        }
                                        try {
                                            String white = White.normalizeTokenWhite(tokenizer.nextToken());
                                            String clientTicket = tokenizer.nextToken();
                                            white = clientTicket + white;
                                            String ip = tokenizer.nextToken();
                                            String sender = tokenizer.nextToken();
                                            String recipient = tokenizer.nextToken();
                                            String hostname = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
                                            if (sentUnblockConfirmationSMTP.containsKey(command.substring(1))) {
                                                type = "WHITE";
                                                code = 200;
                                                result = enviarConfirmacaoDesbloqueio(
                                                        command.substring(1),
                                                        recipient, sender, locale
                                                );
                                            } else if (White.containsExact(white)) {
                                                type = "WHITE";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Já houve liberação deste remetente "
                                                            + "" + sender + " pelo destinatário "
                                                            + "" + recipient + ".";
                                                } else {
                                                    message = "There have been release from this sender "
                                                            + "" + sender + " by recipient "
                                                            + "" + recipient + ".";
                                                }
                                                result = getMessageHMTL(locale, title, message);
                                            } else {
                                                type = "WHITE";
                                                code = 200;
                                                String message;
                                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                    message = "Para desbloquear este remetente " + sender + ", "
                                                            + "resolva o desafio reCAPTCHA abaixo.";
                                                } else {
                                                    message = "To unblock this sender " + sender + ", "
                                                            + "please solve the reCAPTCHA below.";
                                                }
                                                result = getWhiteHMTL(locale, message);
                                            }
                                        } catch (Exception ex) {
                                            type = "WHITE";
                                            code = 500;
                                            String message;
                                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                                message = "Ocorreu um erro no processamento desta solicitação: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            } else {
                                                message = "There was an error processing this request: "
                                                        + ex.getMessage() == null ? "undefined error." : ex.getMessage();
                                            }
                                            result = getMessageHMTL(locale, title, message);
                                        }
                                    } else {
                                        type = "HTTPC";
                                        code = 403;
                                        result = "Forbidden\n";
                                    }
                                }
                            } else {
                                type = "HTTPC";
                                code = 403;
                                result = "Forbidden\n";
                            }
                        } catch (Exception ex) {
                            type = "HTTPC";
                            code = 403;
                            result = "Forbidden\n";
                        }
                    }
                } else if (request.equals("PUT")) {
                    if (command.startsWith("/spam/")) {
                        try {
                            int index = command.indexOf('/', 1) + 1;
                            String ticket = command.substring(index);
                            ticket = URLDecoder.decode(ticket, "UTF-8");
                            TreeSet<String> complainSet = SPF.addComplain(origin, ticket);
                            if (complainSet == null) {
                                type = "SPFSP";
                                code = 404;
                                result = "DUPLICATE COMPLAIN\n";
                            } else {
                                type = "SPFSP";
                                code = 200;
                                String recipient = SPF.getRecipient(ticket);
                                result = "OK " + complainSet + (recipient == null ? "" : " >" + recipient) + "\n";
                            }
                        } catch (Exception ex) {
                            type = "SPFSP";
                            code = 500;
                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                        }
                    } else if (command.startsWith("/ham/")) {
                        try {
                            int index = command.indexOf('/', 1) + 1;
                            String ticket = command.substring(index);
                            ticket = URLDecoder.decode(ticket, "UTF-8");
                            TreeSet<String> tokenSet = SPF.deleteComplain(origin, ticket);
                            if (tokenSet == null) {
                                type = "SPFHM";
                                code = 404;
                                result = "ALREADY REMOVED\n";
                            } else {
                                type = "SPFHM";
                                code = 200;
                                String recipient = SPF.getRecipient(ticket);
                                result = "OK " + tokenSet + (recipient == null ? "" : " >" + recipient) + "\n";
                            }
                        } catch (Exception ex) {
                            type = "SPFHM";
                            code = 500;
                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                        }
                    } else {
                        try {
                            String ticket = command.substring(1);
                            byte[] byteArray = Server.decryptToByteArrayURLSafe(ticket);
                            if (byteArray.length > 8) {
                                long date = byteArray[7] & 0xFF;
                                date <<= 8;
                                date += byteArray[6] & 0xFF;
                                date <<= 8;
                                date += byteArray[5] & 0xFF;
                                date <<= 8;
                                date += byteArray[4] & 0xFF;
                                date <<= 8;
                                date += byteArray[3] & 0xFF;
                                date <<= 8;
                                date += byteArray[2] & 0xFF;
                                date <<= 8;
                                date += byteArray[1] & 0xFF;
                                date <<= 8;
                                date += byteArray[0] & 0xFF;
                                if (System.currentTimeMillis() - date > 432000000) {
                                    type = "HTTPC";
                                    code = 500;
                                    result = "EXPIRED TICKET.\n";
                                } else {
                                    String query = Core.HUFFMAN.decode(byteArray, 8);
                                    StringTokenizer tokenizer = new StringTokenizer(query, " ");
                                    command = tokenizer.nextToken();
                                    if (command.equals("spam")) {
                                        try {
                                            type = "SPFSP";
                                            code = 200;
                                            String sender = null;
                                            String recipient = null;
                                            String clientTicket = null;
                                            TreeSet<String> tokenSet = new TreeSet<String>();
                                            while (tokenizer.hasMoreTokens()) {
                                                String token = tokenizer.nextToken();
                                                if (token.startsWith(">") && Domain.isEmail(token.substring(1))) {
                                                    recipient = token.substring(1);
                                                } else if (token.endsWith(":") && Domain.isEmail(token.substring(0, token.length() - 1))) {
                                                    clientTicket = token.substring(0, token.length() - 1);
                                                } else if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                                                    sender = token;
                                                    tokenSet.add(token);
                                                } else if (Domain.isEmail(token)) {
                                                    sender = token;
                                                    tokenSet.add(token);
                                                } else {
                                                    tokenSet.add(token);
                                                }
                                            }
                                            TreeSet<String> complainSet = SPF.addComplain(origin, date, tokenSet, recipient);
                                            if (complainSet == null) {
                                                result = "DUPLICATE COMPLAIN\n";
                                            } else {
                                                if (clientTicket != null && sender != null && recipient != null) {
                                                    if (White.dropExact(clientTicket + ":" + sender + ";PASS>" + recipient)) {
                                                        Server.logDebug("WHITE DROP " + clientTicket + ":" + sender + ";PASS>" + recipient);
                                                    }
                                                }
                                                result = "OK " + complainSet + (recipient == null ? "" : " >" + recipient) + "\n";
                                            }
                                        } catch (Exception ex) {
                                            type = "SPFSP";
                                            code = 500;
                                            result = ex.getMessage() == null ? "Undefined error." : ex.getMessage() + "\n";
                                        }
                                    } else {
                                        type = "HTTPC";
                                        code = 403;
                                        result = "Forbidden\n";
                                    }
                                }
                            } else {
                                type = "HTTPC";
                                code = 403;
                                result = "Forbidden\n";
                            }
                        } catch (Exception ex) {
                            type = "HTTPC";
                            code = 403;
                            result = "Forbidden\n";
                        }
                    }
                } else {
                    type = "HTTPC";
                    code = 405;
                    result = "Method not allowed.\n";
                }
                if (code > 0) {
                    try {
                        response(code, result, exchange);
                        command = request + " " + command + (parameterMap == null ? "" : " " + parameterMap);
                        result = code + " " + result;
                    } catch (IOException ex) {
                        result = ex.getMessage();
                    }
                }
                Server.logQuery(
                        time, type,
                        origin,
                        command,
                        result
                        );
            } catch (Exception ex) {
                Server.logError(ex);
            } finally {
                exchange.close();
            }
        }
    }
    
    private static final HashMap<String,Object> sentUnblockKeySMTP = new HashMap<String,Object>();
    
    private static String getDesbloqueioHTML(
            final Locale locale,
            final String url,
            final String ip,
            final String email
            ) throws MessagingException {
        StringBuilder builder = new StringBuilder();
        Object resultSentSMTP = sentUnblockKeySMTP.get(ip);
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        String title;
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            title = "Página de checagem DNSBL";
        } else {
            title = "DNSBL check page";
        }
        if (resultSentSMTP == null) {
            if (sentUnblockKeySMTP.containsKey(ip)) {
                buildHead(builder, title, Core.getURL(locale, ip), 5);
            } else {
                sentUnblockKeySMTP.put(ip, null);
                buildHead(builder, title, Core.getURL(locale, ip), 10);
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            Thread.currentThread().setName("BCKGROUND");
                            sentUnblockKeySMTP.put(ip, enviarDesbloqueioDNSBL(locale, url, ip, email));
                        } catch (Exception ex) {
                            sentUnblockKeySMTP.put(ip, ex);
                        }
                    }
                }.start();
            }
        } else {
            buildHead(builder, title);
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        builder.append("      <iframe data-aa='455818' src='//ad.a-ads.com/455818?size=468x60' scrolling='no' style='width:468px; height:60px; border:0px; padding:0;overflow:hidden' allowtransparency='true'></iframe>");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "Envio da chave de desbloqueio");
        } else {
            buildMessage(builder, "Unlocking key delivery");
        }
        if (resultSentSMTP == null) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Estamos enviando a chave de desbloqueio por SMTP. Aguarde...");
            } else {
                 buildText(builder, "We are sending the unlock key by SMTP. Wait...");
            }
        } else if (resultSentSMTP instanceof Boolean) {
            sentUnblockKeySMTP.remove(ip);
            boolean isSentSMTP = (Boolean) resultSentSMTP;
            if (isSentSMTP) {
                if (email == null) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Chave de desbloqueio enviada com sucesso.");
                    } else {
                        buildText(builder, "Unlock key sent successfully.");
                    }
                } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Chave de desbloqueio enviada com sucesso para " + email + ".");
                    } else {
                        buildText(builder, "Unlock key sent successfully to " + email + ".");
                    }
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Não foi possível enviar a chave de desbloqueio devido a uma falha de sistema.");
                } else {
                    buildText(builder, "The unlock key could not be sent due to a system failure.");
                }
            }
        } else if (resultSentSMTP instanceof SendFailedException) {
            sentUnblockKeySMTP.remove(ip);
            SendFailedException ex = (SendFailedException) resultSentSMTP;
            if (ex.getCause() instanceof SMTPAddressFailedException) {
                if (email == null) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Chave de desbloqueio não pode ser enviada porque o endereço de destino não existe.");
                    } else {
                        buildText(builder, "Unlock key can not be sent because the destiny address does not exist.");
                    }
                } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Chave de desbloqueio não pode ser enviada porque o endereço " + email + " não existe.");
                    } else {
                        buildText(builder, "Unlock key can not be sent because the " + email + " address does not exist.");
                    }
                }
            } else if (ex.getCause() == null) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Chave de desbloqueio não pode ser enviada devido a recusa do servidor de destino.");
                } else {
                    buildText(builder, "Unlock key can not be sent due to denial of destination server.");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Chave de desbloqueio não pode ser enviada devido a recusa do servidor de destino:\n");
                    buildText(builder, ex.getCause().getMessage());
                } else {
                    buildText(builder, "Unlock key can not be sent due to denial of destination server:\n");
                    buildText(builder, ex.getCause().getMessage());
                }
            }
        } else if (resultSentSMTP instanceof NameNotFoundException) {
            sentUnblockKeySMTP.remove(ip);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Chave de desbloqueio não pode ser enviada pois o MX de destino não existe.");
            } else {
                buildText(builder, "Unlock key can not be sent because the destination MX does not exist.");
            }
        } else if (resultSentSMTP instanceof MailConnectException) {
            sentUnblockKeySMTP.remove(ip);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Chave de desbloqueio não pode ser enviada pois o MX de destino se encontra indisponível.");
            } else {
                buildText(builder, "Unlock key can not be sent because the destination MX is unavailable.");
            }
        } else if (resultSentSMTP instanceof MessagingException) {
            sentUnblockKeySMTP.remove(ip);
            MessagingException ex = (MessagingException) resultSentSMTP;
            if (ex.getCause() instanceof SocketTimeoutException) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Chave de desbloqueio não pode ser enviada pois o MX de destino demorou demais para iniciar a transação SMTP.");
                    buildText(builder, "Para que o envio da chave seja possível, o inicio da transação SMTP não pode levar mais que 30 segundos.");
                } else {
                    buildText(builder, "Unlock key can not be sent because the destination MX has taken too long to initiate the SMTP transaction.");
                    buildText(builder, "For the key delivery is possible, the beginning of the SMTP transaction can not take more than 30 seconds.");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Chave de desbloqueio não pode ser enviada pois o MX de destino está recusando nossa mensagem.");
                } else {
                    buildText(builder, "Unlock key can not be sent because the destination MX is declining our message.");
                }
            }
        } else {
            sentUnblockKeySMTP.remove(ip);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Não foi possível enviar a chave de desbloqueio devido a uma falha de sistema.");
            } else {
                buildText(builder, "The unlock key could not be sent due to a system failure.");
            }
        }
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static boolean enviarDesbloqueioDNSBL(
            Locale locale,
            String url,
            String ip,
            String email
            ) throws MessagingException, NameNotFoundException {
        if (url == null) {
            return false;
        } else if (!Core.hasOutputSMTP()) {
            return false;
        } else if (!Core.hasAdminEmail()) {
            return false;
        } else if (!Domain.isEmail(email)) {
            return false;
        } else if (NoReply.contains(email, true)) {
            return false;
        } else {
            try {
                Server.logDebug("sending unblock by e-mail.");
                User user = User.get(email);
                InternetAddress[] recipients;
                if (user == null) {
                    recipients = InternetAddress.parse(email);
                } else {
                    recipients = new InternetAddress[1];
                    recipients[0] = user.getInternetAddress();
                }
//                Properties props = System.getProperties();
//                Session session = Session.getDefaultInstance(props);
//                MimeMessage message = new MimeMessage(session);
//                message.setHeader("Date", Core.getEmailDate());
//                message.setFrom(Core.getAdminInternetAddress());
                MimeMessage message = Core.newMessage();
                message.addRecipients(Message.RecipientType.TO, recipients);
                String subject;
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    subject = "Chave de desbloqueio SPFBL";
                } else {
                    subject = "Unblocking key SPFBL";
                }
                message.setSubject(subject);
                // Corpo da mensagem.
                StringBuilder builder = new StringBuilder();
                builder.append("<!DOCTYPE html>\n");
                builder.append("<html lang=\"");
                builder.append(locale.getLanguage());
                builder.append("\">\n");
                builder.append("  <head>\n");
                builder.append("    <meta charset=\"UTF-8\">\n");
                builder.append("    <title>");
                builder.append(subject);
                builder.append("</title>\n");
                loadStyleCSS(builder);
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildConfirmAction(
                            builder,
                            "Desbloquear IP",
                            url,
                            "Confirme o desbloqueio para o IP " + ip + " na DNSBL",
                            "SPFBL.net", "http://spfbl.net/"
                    );
                } else {
                    buildConfirmAction(
                            builder,
                            "Delist IP",
                            url,
                            "Confirm the delist of IP " + ip + " at DNSBL",
                            "SPFBL.net",
                            "http://spfbl.net/en/"
                    );
                }
                builder.append("  </head>\n");
                builder.append("  <body>\n");
                builder.append("    <div id=\"container\">\n");
                builder.append("      <div id=\"divlogo\">\n");
                builder.append("        <img src=\"cid:logo\">\n");
                builder.append("      </div>\n");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildMessage(builder, "Desbloqueio do IP " + ip + " na DNSBL");
                    buildText(builder, "Se você é o administrador deste IP, e fez esta solicitação, acesse esta URL e resolva o reCAPTCHA para finalizar o procedimento:");
                } else {
                    buildMessage(builder, "Unblock of IP " + ip + " at DNSBL");
                    buildText(builder, "If you are the administrator of this IP and made this request, go to this URL and solve the reCAPTCHA to finish the procedure:");
                }
                buildText(builder, "<a href=\"" + url + "\">" + url + "</a>");
                buildFooter(builder, locale, Core.getListUnsubscribeURL(locale, recipients[0]));
                builder.append("    </div>\n");
                builder.append("  </body>\n");
                builder.append("</html>\n");
                // Making HTML part.
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(builder.toString(), "text/html;charset=UTF-8");
                // Making logo part.
                MimeBodyPart logoPart = new MimeBodyPart();
                File logoFile = getWebFile("logo.png");
                logoPart.attachFile(logoFile);
                logoPart.setContentID("<logo>");
                logoPart.addHeader("Content-Type", "image/png");
                logoPart.setDisposition(MimeBodyPart.INLINE);
                // Join both parts.
                MimeMultipart content = new MimeMultipart("related");
                content.addBodyPart(htmlPart);
                content.addBodyPart(logoPart);
                // Set multiplart content.
                message.setContent(content);
                message.saveChanges();
                // Enviar mensagem.
                return Core.sendMessage(locale, message, 30000);
            } catch (CommunicationException ex) {
                return false;
            } catch (NameNotFoundException ex) {
                throw ex;
            } catch (MailConnectException ex) {
                throw ex;
            } catch (SendFailedException ex) {
                throw ex;
            } catch (MessagingException ex) {
                throw ex;
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        }
    }
    
    public static boolean enviarOTP(
            Locale locale,
            User user
            ) {
        if (locale == null) {
            Server.logError("no locale defined.");
            return false;
        } else if (!Core.hasOutputSMTP()) {
            Server.logError("no SMTP account to send TOTP.");
            return false;
        } else if (!Core.hasAdminEmail()) {
            Server.logError("no admin e-mail to send TOTP.");
            return false;
        } else if (user == null) {
            Server.logError("no user definied to send TOTP.");
            return false;
        } else if (NoReply.contains(user.getEmail(), true)) {
            Server.logError("cannot send TOTP because user is registered in noreply.");
            return false;
        } else {
            try {
                Server.logDebug("sending TOTP by e-mail.");
                String secret = user.newSecretOTP();
                InternetAddress[] recipients = new InternetAddress[1];
                recipients[0] = user.getInternetAddress();
//                Properties props = System.getProperties();
//                Session session = Session.getDefaultInstance(props);
//                MimeMessage message = new MimeMessage(session);
//                message.setHeader("Date", Core.getEmailDate());
//                message.setFrom(Core.getAdminInternetAddress());
                MimeMessage message = Core.newMessage();
                message.addRecipients(Message.RecipientType.TO, recipients);
                String subject;
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    subject = "Chave TOTP do SPFBL";
                } else {
                    subject = "SPFBL TOTP key";
                }
                message.setSubject(subject);
                // Corpo da mensagem.
                StringBuilder builder = new StringBuilder();
                builder.append("<!DOCTYPE html>\n");
                builder.append("<html lang=\"");
                builder.append(locale.getLanguage());
                builder.append("\">\n");
                builder.append("  <head>\n");
                builder.append("    <meta charset=\"UTF-8\">\n");
                builder.append("    <title>");
                builder.append(subject);
                builder.append("</title>\n");
                loadStyleCSS(builder);
                builder.append("  </head>\n");
                builder.append("  <body>\n");
                builder.append("    <div id=\"container\">\n");
                builder.append("      <div id=\"divlogo\">\n");
                builder.append("        <img src=\"cid:logo\">\n");
                builder.append("      </div>\n");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildMessage(builder, "Sua chave <a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> no sistema SPFBL em " + Core.getHostname());
                    buildText(builder, "Carregue o QRCode abaixo em seu Google Authenticator ou em outro aplicativo <a target=\"_blank\" href=\"http://spfbl.net/totp/\">TOTP</a> de sua preferência.");
                    builder.append("      <div id=\"divcaptcha\">\n");
                    builder.append("        <img src=\"cid:qrcode\"><br>\n");
                    builder.append("        ");
                    builder.append(secret);
                    builder.append("\n");
                    builder.append("      </div>\n");
                } else {
                    buildMessage(builder, "Your <a target=\"_blank\" href=\"http://spfbl.net/en/totp/\">TOTP</a> key in SPFBL system at " + Core.getHostname());
                    buildText(builder, "Load QRCode below on your Google Authenticator or on other application <a target=\"_blank\" href=\"http://spfbl.net/en/totp/\">TOTP</a> of your choice.");
                    builder.append("      <div id=\"divcaptcha\">\n");
                    builder.append("        <img src=\"cid:qrcode\"><br>\n");
                    builder.append("        ");
                    builder.append(secret);
                    builder.append("\n");
                    builder.append("      </div>\n");
                }
                buildFooter(builder, locale, Core.getListUnsubscribeURL(locale, recipients[0]));
                builder.append("    </div>\n");
                builder.append("  </body>\n");
                builder.append("</html>\n");
                // Making HTML part.
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(builder.toString(), "text/html;charset=UTF-8");
                // Making logo part.
                MimeBodyPart logoPart = new MimeBodyPart();
                File logoFile = getWebFile("logo.png");
                logoPart.attachFile(logoFile);
                logoPart.setContentID("<logo>");
                logoPart.addHeader("Content-Type", "image/png");
                logoPart.setDisposition(MimeBodyPart.INLINE);
                // Making QRcode part.
                MimeBodyPart qrcodePart = new MimeBodyPart();
                String code = "otpauth://totp/" + Core.getHostname() + ":" + user.getEmail() + "?"
                        + "secret=" + secret + "&"
                        + "issuer=" + Core.getHostname();
                File qrcodeFile = Core.getQRCodeTempFile(code);
                qrcodePart.attachFile(qrcodeFile);
                qrcodePart.setContentID("<qrcode>");
                qrcodePart.addHeader("Content-Type", "image/png");
                qrcodePart.setDisposition(MimeBodyPart.INLINE);
                // Join both parts.
                MimeMultipart content = new MimeMultipart("related");
                content.addBodyPart(htmlPart);
                content.addBodyPart(logoPart);
                content.addBodyPart(qrcodePart);
                // Set multiplart content.
                message.setContent(content);
                message.saveChanges();
                // Enviar mensagem.
                boolean sent = Core.sendMessage(locale, message, 30000);
                qrcodeFile.delete();
                return sent;
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        }
    }
    
    private static boolean enviarDesbloqueio(
            String url,
            String remetente,
            String destinatario
            ) throws SendFailedException, MessagingException {
        if (url == null) {
            return false;
        } else if (!Core.hasOutputSMTP()) {
            return false;
        } else if (!Domain.isEmail(destinatario)) {
            return false;
        } else if (NoReply.contains(destinatario, true)) {
            return false;
        } else {
            try {
                Server.logDebug("sending unblock by e-mail.");
                Locale locale = User.getLocale(destinatario);
                InternetAddress[] recipients = InternetAddress.parse(destinatario);
//                Properties props = System.getProperties();
//                Session session = Session.getDefaultInstance(props);
//                MimeMessage message = new MimeMessage(session);
//                message.setHeader("Date", Core.getEmailDate());
//                message.setFrom(Core.getAdminInternetAddress());
                MimeMessage message = Core.newMessage();
                message.setReplyTo(InternetAddress.parse(remetente));
                message.addRecipients(Message.RecipientType.TO, recipients);
                String subject;
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    subject = "Solicitação de envio SPFBL";
                } else {
                    subject = "SPFBL send request";
                }
                message.setSubject(subject);
                // Corpo da mensagem.
                StringBuilder builder = new StringBuilder();
                builder.append("<!DOCTYPE html>\n");
                builder.append("<html lang=\"");
                builder.append(locale.getLanguage());
                builder.append("\">\n");
                builder.append("  <head>\n");
                builder.append("    <meta charset=\"UTF-8\">\n");
                builder.append("    <title>");
                builder.append(subject);
                builder.append("</title>\n");
                loadStyleCSS(builder);
                builder.append("  </head>\n");
                builder.append("  <body>\n");
                builder.append("    <div id=\"container\">\n");
                builder.append("      <div id=\"divlogo\">\n");
                builder.append("        <img src=\"cid:logo\">\n");
                builder.append("      </div>\n");
                buildMessage(builder, subject);
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Nosso servidor recusou uma ou mais mensagens do remetente " + remetente + " e o mesmo requisitou que seja feita a liberação para que novos e-mails possam ser entregues a você.");
                    buildText(builder, "Se você deseja receber e-mails de " + remetente + ", acesse o endereço abaixo e para iniciar o processo de liberação:");
                } else {
                    buildText(builder, "Our server has refused one or more messages from the sender " + remetente + " and the same sender has requested that the release be made for new emails can be delivered to you.");
                    buildText(builder, "If you wish to receive emails from " + remetente + ", access the address below and to start the release process:");
                }
                buildText(builder, "<a href=\"" + url + "\">" + url + "</a>");
                buildFooter(builder, locale, Core.getListUnsubscribeURL(locale, recipients[0]));
                builder.append("    </div>\n");
                builder.append("  </body>\n");
                builder.append("</html>\n");
                // Making HTML part.
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(builder.toString(), "text/html;charset=UTF-8");
                // Making logo part.
                MimeBodyPart logoPart = new MimeBodyPart();
                File logoFile = getWebFile("logo.png");
                logoPart.attachFile(logoFile);
                logoPart.setContentID("<logo>");
                logoPart.addHeader("Content-Type", "image/png");
                logoPart.setDisposition(MimeBodyPart.INLINE);
                // Join both parts.
                MimeMultipart content = new MimeMultipart("related");
                content.addBodyPart(htmlPart);
                content.addBodyPart(logoPart);
                // Set multiplart content.
                message.setContent(content);
                message.saveChanges();
                // Enviar mensagem.
                return Core.sendMessage(locale, message, 30000);
            } catch (MailConnectException ex) {
                throw ex;
            } catch (SendFailedException ex) {
                throw ex;
            } catch (MessagingException ex) {
                throw ex;
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        }
    }
    
//    private static boolean enviarConfirmacaoDesbloqueio(
//            String destinatario,
//            String remetente,
//            Locale locale
//            ) {
//        if (!Core.hasOutputSMTP()) {
//            return false;
//        } else if (!Core.hasAdminEmail()) {
//            return false;
//        } else if (!Domain.isEmail(remetente)) {
//            return false;
//        } else if (NoReply.contains(remetente, true)) {
//            return false;
//        } else {
//            try {
//                Server.logDebug("sending unblock confirmation by e-mail.");
//                InternetAddress[] recipients = InternetAddress.parse(remetente);
////                Properties props = System.getProperties();
////                Session session = Session.getDefaultInstance(props);
////                MimeMessage message = new MimeMessage(session);
////                message.setHeader("Date", Core.getEmailDate());
////                message.setFrom(Core.getAdminInternetAddress());
//                MimeMessage message = Core.newMessage();
//                message.setReplyTo(InternetAddress.parse(destinatario));
//                message.addRecipients(Message.RecipientType.TO, recipients);
//                String subject;
//                if (locale.getLanguage().toLowerCase().equals("pt")) {
//                    subject = "Confirmação de desbloqueio SPFBL";
//                } else {
//                    subject = "SPFBL unblocking confirmation";
//                }
//                message.setSubject(subject);
//                // Corpo da mensagem.
//                StringBuilder builder = new StringBuilder();
//                builder.append("<!DOCTYPE html>\n");
//                builder.append("<html lang=\"");
//                builder.append(locale.getLanguage());
//                builder.append("\">\n");
//                builder.append("  <head>\n");
//                builder.append("    <meta charset=\"UTF-8\">\n");
//                builder.append("    <title>");
//                builder.append(subject);
//                builder.append("</title>\n");
//                loadStyleCSS(builder);
//                builder.append("  </head>\n");
//                builder.append("  <body>\n");
//                builder.append("    <div id=\"container\">\n");
//                builder.append("      <div id=\"divlogo\">\n");
//                builder.append("        <img src=\"cid:logo\">\n");
//                builder.append("      </div>\n");
//                buildMessage(builder, subject);
//                if (locale.getLanguage().toLowerCase().equals("pt")) {
//                    buildText(builder, "O destinatário '" + destinatario + "' acabou de liberar o recebimento de suas mensagens.");
//                    buildText(builder, "Por favor, envie novamente a mensagem anterior.");
//                } else {
//                    buildText(builder, "The recipient '" + destinatario + "' just released the receipt of your message.");
//                    buildText(builder, "Please send the previous message again.");
//                }
//                buildFooter(builder, locale, Core.getListUnsubscribeURL(locale, recipients[0]));
//                builder.append("    </div>\n");
//                builder.append("  </body>\n");
//                builder.append("</html>\n");
//                // Making HTML part.
//                MimeBodyPart htmlPart = new MimeBodyPart();
//                htmlPart.setContent(builder.toString(), "text/html;charset=UTF-8");
//                // Making logo part.
//                MimeBodyPart logoPart = new MimeBodyPart();
//                File logoFile = getWebFile("logo.png");
//                logoPart.attachFile(logoFile);
//                logoPart.setContentID("<logo>");
//                logoPart.addHeader("Content-Type", "image/png");
//                logoPart.setDisposition(MimeBodyPart.INLINE);
//                // Join both parts.
//                MimeMultipart content = new MimeMultipart("related");
//                content.addBodyPart(htmlPart);
//                content.addBodyPart(logoPart);
//                // Set multiplart content.
//                message.setContent(content);
//                message.saveChanges();
//                // Enviar mensagem.
//                return Core.sendMessage(locale, message, 30000);
//            } catch (MessagingException ex) {
//                return false;
//            } catch (Exception ex) {
//                Server.logError(ex);
//                return false;
//            }
//        }
//    }
    
    private static boolean enviarConfirmacaoDesbloqueio(
            String destinatario,
            String remetente,
            Locale locale
            ) throws Exception  {
        if (!Core.hasOutputSMTP()) {
            return false;
        } else if (!Core.hasAdminEmail()) {
            return false;
        } else if (!Domain.isEmail(remetente)) {
            return false;
        } else if (NoReply.contains(remetente, true)) {
            return false;
        } else {
            Server.logDebug("sending unblock confirmation by e-mail.");
            InternetAddress[] recipients = InternetAddress.parse(remetente);
//            Properties props = System.getProperties();
//            Session session = Session.getDefaultInstance(props);
//            MimeMessage message = new MimeMessage(session);
//            message.setHeader("Date", Core.getEmailDate());
//            message.setFrom(Core.getAdminInternetAddress());
            MimeMessage message = Core.newMessage();
            message.setReplyTo(InternetAddress.parse(destinatario));
            message.addRecipients(Message.RecipientType.TO, recipients);
            String subject;
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                subject = "Confirmação de desbloqueio SPFBL";
            } else {
                subject = "SPFBL unblocking confirmation";
            }
            message.setSubject(subject);
            // Corpo da mensagem.
            StringBuilder builder = new StringBuilder();
            builder.append("<!DOCTYPE html>\n");
            builder.append("<html lang=\"");
            builder.append(locale.getLanguage());
            builder.append("\">\n");
            builder.append("  <head>\n");
            builder.append("    <meta charset=\"UTF-8\">\n");
            builder.append("    <title>");
            builder.append(subject);
            builder.append("</title>\n");
            loadStyleCSS(builder);
            builder.append("  </head>\n");
            builder.append("  <body>\n");
            builder.append("    <div id=\"container\">\n");
            builder.append("      <div id=\"divlogo\">\n");
            builder.append("        <img src=\"cid:logo\">\n");
            builder.append("      </div>\n");
            buildMessage(builder, subject);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "O destinatário " + destinatario + " acabou de liberar o recebimento de suas mensagens.");
                buildText(builder, "Por favor, envie novamente a mensagem anterior.");
            } else {
                buildText(builder, "The recipient " + destinatario + " just released the receipt of your message.");
                buildText(builder, "Please send the previous message again.");
            }
            buildFooter(builder, locale, Core.getListUnsubscribeURL(locale, recipients[0]));
            builder.append("    </div>\n");
            builder.append("  </body>\n");
            builder.append("</html>\n");
            // Making HTML part.
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(builder.toString(), "text/html;charset=UTF-8");
            // Making logo part.
            MimeBodyPart logoPart = new MimeBodyPart();
            File logoFile = getWebFile("logo.png");
            logoPart.attachFile(logoFile);
            logoPart.setContentID("<logo>");
            logoPart.addHeader("Content-Type", "image/png");
            logoPart.setDisposition(MimeBodyPart.INLINE);
            // Join both parts.
            MimeMultipart content = new MimeMultipart("related");
            content.addBodyPart(htmlPart);
            content.addBodyPart(logoPart);
            // Set multiplart content.
            message.setContent(content);
            message.saveChanges();
            // Enviar mensagem.
            return Core.sendMessage(locale, message, 30000);
        }
    }
    
    private static final HashMap<String,Object> sentUnblockConfirmationSMTP = new HashMap<String,Object>();
    
    private static String enviarConfirmacaoDesbloqueio(
            final String command,
            final String destinatario,
            final String remetente,
            final Locale locale
            ) {
        StringBuilder builder = new StringBuilder();
        Object resultSentSMTP = sentUnblockConfirmationSMTP.get(command);
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        String title;
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            title = "Página de desbloqueio de remetente";
        } else {
            title = "Sender unblock page";
        }
        if (resultSentSMTP == null) {
            if (sentUnblockConfirmationSMTP.containsKey(command)) {
                buildHead(builder, title, Core.getURL(locale, command), 5);
            } else {
                sentUnblockConfirmationSMTP.put(command, null);
                buildHead(builder, title, Core.getURL(locale, command), 10);
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            Thread.currentThread().setName("BCKGROUND");
                            sentUnblockConfirmationSMTP.put(command, enviarConfirmacaoDesbloqueio(destinatario, remetente, locale));
                        } catch (Exception ex) {
                            sentUnblockConfirmationSMTP.put(command, ex);
                        }
                    }
                }.start();
            }
        } else {
            buildHead(builder, title);
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "Remetente desbloqueado com sucesso");
        } else {
            buildMessage(builder, "Sender successfully unlocked");
        }
        if (resultSentSMTP == null) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Estamos enviando a confirmação de desbloqueio ao remetente. Aguarde...");
            } else {
                 buildText(builder, "We're sending the unblock confirmation to the sender. Wait...");
            }
        } else if (resultSentSMTP instanceof Boolean) {
            sentUnblockConfirmationSMTP.remove(command);
            boolean isSentSMTP = (Boolean) resultSentSMTP;
            if (isSentSMTP) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Confirmação de desbloqueio enviada com sucesso para " + remetente + ".");
                    buildText(builder, "Por favor, aguarde pelo reenvio das mensagens rejeitadas anteriormente.");
                } else {
                    buildText(builder, "Unblock confirmation sent successfully to " + remetente + ".");
                    buildText(builder, "Please, wait for the previously rejected messages to be resent.");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Não foi possível eviar a confirmação de desbloqueio para " + remetente + " devido a uma falha de sistema.");
                    buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
                } else {
                    buildText(builder, "Unable to send unlock confirmation to " + remetente + " due to system crash.");
                    buildText(builder, "Please, use other media to inform the sender to resend his last message.");
                }
            }
        } else if (resultSentSMTP instanceof SendFailedException) {
            sentUnblockConfirmationSMTP.remove(command);
            SendFailedException ex = (SendFailedException) resultSentSMTP;
            if (ex.getCause() instanceof SMTPAddressFailedException) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "A confirmação de desbloqueio não pode ser enviada para " + remetente + " porque este endereço não existe.");
                    buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
                } else {
                    buildText(builder, "The unblock confirmation can not be sent to " + remetente + " because this address does not exist.");
                    buildText(builder, "Please, use other media to inform the sender to resend his last message.");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "A confirmação de desbloqueio não pode ser enviada para " + remetente + " devido a recusa do servidor do remetente.");
                    buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
                } else {
                    buildText(builder, "The unblock confirmation can not be sent to " + remetente + " due to denial of the sender's server.");
                    buildText(builder, "Please, use other media to inform the sender to resend his last message.");
                }
            }
        } else if (resultSentSMTP instanceof NameNotFoundException) {
            sentUnblockConfirmationSMTP.remove(command);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "A confirmação de desbloqueio não pode ser enviada para " + remetente + " porque o servidor do remetente não pode ser encontrado.");
                buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
            } else {
                buildText(builder, "The unblock confirmation can not be sent to " + remetente + " because the sender's server can not be found.");
                buildText(builder, "Please, use other media to inform the sender to resend his last message.");
            }
        } else if (resultSentSMTP instanceof MailConnectException) {
            sentUnblockConfirmationSMTP.remove(command);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "A confirmação de desbloqueio não pode ser enviada para " + remetente + " porque o servidor do remetente se encontra indisponível.");
                buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
            } else {
                buildText(builder, "The unblocking confirmation can not be sent to " + remetente + " because the sender's server is unavailable.");
                buildText(builder, "Please, use other media to inform the sender to resend his last message.");
            }
        } else if (resultSentSMTP instanceof MessagingException) {
            sentUnblockConfirmationSMTP.remove(command);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "A confirmação de desbloqueio não pode ser enviada para " + remetente + " pois o servidor do remetente está recusando nossa mensagem.");
                buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
            } else {
                buildText(builder, "The unblock confirmation can not be sent to " + remetente + " because the sender's server is declining our message.");
                buildText(builder, "Please, use other media to inform the sender to resend his last message.");
            }
        } else {
            sentUnblockConfirmationSMTP.remove(command);
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Não foi possível enviar a confirmação de desbloqueio para " + remetente + " devido a uma falha do nosso sistema.");
                buildText(builder, "Por favor, utilize outros meios de comunicação para informar o remetente para reenviar sua última mensagem.");
            } else {
                buildText(builder, "Unable to send unblock confirmation to " + remetente + " due to a crash in our system.");
                buildText(builder, "Please, use other media to inform the sender to resend his last message.");
            }
        }
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getUnblockHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de desbloqueio do SPFBL");
        } else {
            buildHead(builder, "SPFBL unlock page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "A sua mensagem está sendo rejeitada por bloqueio manual");
        } else {
            buildMessage(builder, "Your message is being rejected by manual block");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Solicitar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Request\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getUnblockDNSBLHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de desbloqueio DNSBL");
        } else {
            buildHead(builder, "DNSBL unblock page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        builder.append("      <iframe data-aa='455818' src='//ad.a-ads.com/455818?size=468x60' scrolling='no' style='width:468px; height:60px; border:0px; padding:0;overflow:hidden' allowtransparency='true'></iframe>\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "Página de desbloqueio DNSBL");
        } else {
            buildMessage(builder, "DNSBL unblock page");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Desbloquear\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Unblock\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getWhiteHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de desbloqueio do SPFBL");
        } else {
            buildHead(builder, "SPFBL unblock page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "Este remetente foi bloqueado no sistema SPFBL");
        } else {
            buildMessage(builder, "The sender has been blocked in SPFBL system");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Liberar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Release\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getLoginOTPHMTL(
            Locale locale,
            String message,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de login do SPFBL");
        } else {
            buildHead(builder, "SPFBL login page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        builder.append("          <input type=\"password\" name=\"otp\" autofocus><br>\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Entrar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Login\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getSendOTPHMTL(
            Locale locale,
            String message,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de login do SPFBL");
        } else {
            buildHead(builder, "SPFBL login page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Enviar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Send\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }

    private static String getReleaseHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de liberação SPFBL");
        } else {
            buildHead(builder, "SPFBL release page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "O recebimento da sua mensagem está sendo atrasado por suspeita de SPAM");
        } else {
            buildMessage(builder, "The receipt of your message is being delayed by SPAM suspect");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Liberar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Release\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getRequestHoldHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de liberação SPFBL");
        } else {
            buildHead(builder, "SPFBL release page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "A mensagem retida por suspeita de SPAM");
        } else {
            buildMessage(builder, "The message retained on suspicion of SPAM");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Solicitar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Request\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getReleaseHoldHMTL(
            Locale locale,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de liberação SPFBL");
        } else {
            buildHead(builder, "SPFBL release page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildMessage(builder, "A mensagem retida por suspeita de SPAM");
        } else {
            buildMessage(builder, "The message retained on suspicion of SPAM");
        }
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Liberar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Release\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getBlockHMTL(
            Locale locale,
            String message,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de bloqueio SPFBL");
        } else {
            buildHead(builder, "SPFBL block page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Bloquear\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Block\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getUnsubscribeHMTL(
            Locale locale,
            String message,
            String text
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de cancelamento SPFBL");
        } else {
            buildHead(builder, "SPFBL unsubscribe page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        buildText(builder, text);
        builder.append("      <div id=\"divcaptcha\">\n");
        builder.append("        <form method=\"POST\">\n");
        if (Core.hasRecaptchaKeys()) {
            String recaptchaKeySite = Core.getRecaptchaKeySite();
            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
            builder.append(captcha.createRecaptchaHtml(null, null));
            // novo reCAPCHA
//            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
//            builder.append(recaptchaKeySite);
//            builder.append("\"></div>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Cancelar\">\n");
        } else {
            builder.append("           <input id=\"btngo\" type=\"submit\" value=\"Unsubscribe\">\n");
        }
        builder.append("        </form>\n");
        builder.append("      </div>\n");
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getRedirectHMTL(
            Locale locale,
            String title,
            String message,
            String page,
            int time
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        buildHead(builder, title, page, time);
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }

    private static String getMessageHMTL(
            Locale locale,
            String title,
            String message
    ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        buildHead(builder, title);
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, title);
        buildText(builder, message);
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static TreeSet<String> getPostmaterSet(String query) {
        TreeSet<String> emailSet = new TreeSet<String>();
        if (Subnet.isValidIP(query)) {
            String ip = Subnet.normalizeIP(query);
            Reverse reverse = Reverse.get(ip);
            TreeSet<String> reverseSet = reverse.getAddressSet();
            if (!reverseSet.isEmpty()) {
                String hostname = reverseSet.pollFirst();
                do  {
                    hostname = Domain.normalizeHostname(hostname, true);
                    if (!hostname.endsWith(".arpa")) {
                        String domain;
                        try {
                            domain = Domain.extractDomain(hostname, true);
                        } catch (ProcessException ex) {
                            domain = null;
                        }
                        if (domain != null) {
                            String email = "postmaster@" + domain.substring(1);
                            if (!NoReply.contains(email, true)) {
                                emailSet.add(email);
                            }
                            if (!Generic.containsGeneric(hostname) && SPF.matchHELO(ip, hostname)) {
                                String subdominio = hostname;
                                while (!subdominio.equals(domain)) {
                                    email = "postmaster@" + subdominio.substring(1);
                                    if (!NoReply.contains(email, true)) {
                                        emailSet.add(email);
                                    }
                                    int index = subdominio.indexOf('.', 1);
                                    subdominio = subdominio.substring(index);
                                }
                            }
                        }
                    }
                } while ((hostname = reverseSet.pollFirst()) != null);
            }
        } else if (Domain.isHostname(query)) {
            String hostname = Domain.normalizeHostname(query, false);
            String domain;
            try {
                domain = Domain.extractDomain(hostname, false);
            } catch (ProcessException ex) {
                domain = null;
            }
            if (domain != null) {
                String subdominio = hostname;
                while (subdominio.endsWith(domain)) {
                    String email = "postmaster@" + subdominio;
                    if (!NoReply.contains(email, true)) {
                        emailSet.add(email);
                    }
                    int index = subdominio.indexOf('.', 1) + 1;
                    subdominio = subdominio.substring(index);
                }
            }
        }
        return emailSet;
    }
    
    private static final HashMap<String,Boolean> openSMTP = new HashMap<String,Boolean>();
    
    private static String getDNSBLHTML(
            Locale locale,
            Client client,
            final String query,
            String message
            ) {
        StringBuilder builder = new StringBuilder();
        boolean isSLAAC = SubnetIPv6.isSLAAC(query) && !Subnet.isReservedIP(query);
        Boolean isOpenSMTP = openSMTP.get(query);
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        String title;
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            title = "Página de checagem DNSBL";
        } else {
            title = "DNSBL check page";
        }
        if (isSLAAC && isOpenSMTP == null) {
            if (openSMTP.containsKey(query)) {
                buildHead(builder, title, Core.getURL(locale, query), 5);
            } else {
                buildHead(builder, title, Core.getURL(locale, query), 10);
                openSMTP.put(query, null);
                new Thread() {
                    @Override
                    public void run() {
                        Thread.currentThread().setName("BCKGROUND");
                        openSMTP.put(query, Analise.isOpenSMTP(query, 30000));
                    }
                }.start();
            }
        } else {
            buildHead(builder, title);
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        builder.append("      <iframe data-aa='455818' src='//ad.a-ads.com/455818?size=468x60' scrolling='no' style='width:468px; height:60px; border:0px; padding:0;overflow:hidden' allowtransparency='true'></iframe>");
        buildMessage(builder, message);
        TreeSet<String> emailSet = new TreeSet<String>();
        if (Subnet.isValidIP(query)) {
            String ip = Subnet.normalizeIP(query);
            if (Subnet.isReservedIP(ip)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este é um IP reservado e por este motivo não é abordado nesta lista.");
                } else {
                    buildText(builder, "This is a reserved IP and for this reason is not addressed in this list.");
                }
            } else if (isSLAAC && isOpenSMTP == null) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este IP contém sinalização de autoconfiguração de endereço de rede (SLAAC).");
                    buildText(builder, "Estamos verificando se existe serviço SMTP neste IP. Aguarde...");
                } else {
                    buildText(builder, "This IP contains network address autoconfiguration flag (SLAAC).");
                    buildText(builder, "We are checking if there is SMTP service on this IP. Wait...");
                }
            } else if (isSLAAC && !isOpenSMTP) {
                openSMTP.remove(query);
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este IP contém sinalização de autoconfiguração de endereço de rede (SLAAC) mas não foi possível verificar se existe um serviço de e-mail válido nele.");
                    buildText(builder, "Se este IP está sendo usado por um servidor de e-mail legítimo, abra a porta 25 para que possamos verificar que existe um serviço SMTP nele.");
                } else {
                    buildText(builder, "This IP contains network address autoconfiguration flag (SLAAC) but it was not possible to verify that there is a valid email service on it.");
                    buildText(builder, "If this IP is being used by genuine email server, open port 25 so we can check that there is a SMTP service on it.");
                }
            } else {
                openSMTP.remove(query);
                boolean generic = false;
                Reverse reverse = Reverse.get(ip, true);
                TreeSet<String> reverseSet = reverse.getAddressSet();
                if (reverseSet.isEmpty()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Nenhum <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> foi encontrado.");
                    } else {
                        buildText(builder, "No <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> was found.");
                    }
                } else {
                    if (reverseSet.size() == 1) {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Este é o <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> encontrado:");
                        } else {
                            buildText(builder, "This is the <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> found:");
                        }
                    } else if (reverseSet.size() > 16) {
                        while (reverseSet.size() > 16) {
                            reverseSet.pollLast();
                        }
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Estes são os 16 primeiros <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> encontrados:");
                        } else {
                            buildText(builder, "These are the first 16 <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> found:");
                        }
                    } else {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Estes são os <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> encontrados:");
                        } else {
                            buildText(builder, "These are the <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> found:");
                        }
                    }
                    Client abuseClient = Client.getByIP(ip);
                    String abuseEmail = abuseClient == null || abuseClient.hasPermission(NONE) ? null : abuseClient.getEmail();
                    String clientEmail = client == null || client.hasPermission(NONE) ? null : client.getEmail();
                    builder.append("        <ul>\n");
                    String hostname = reverseSet.pollFirst();
                    do {
                        hostname = Domain.normalizeHostname(hostname, false);
                        builder.append("          <li>&lt;");
                        builder.append(hostname);
                        builder.append("&gt; ");
                        if (Generic.containsDynamic(hostname)) {
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("<a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> ");
                                builder.append("de <a target=\"_blank\" href=\"http://spfbl.net/dynamic/\">IP dinâmico</a>.</li>\n");
                            } else {
                                builder.append("<a target=\"_blank\" href=\"http://spfbl.net/en/dynamic/\">dynamic IP</a> ");
                                builder.append("<a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a>.</li>\n");
                            }
                        } else if (SPF.matchHELO(ip, hostname, true)) {
                            String domain;
                            try {
                                domain = Domain.extractDomain(hostname, false);
                            } catch (ProcessException ex) {
                                domain = null;
                            }
                            if (domain == null) {
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    builder.append("domínio reservado.</li>\n");
                                } else {
                                    builder.append("reserved domain.</li>\n");
                                }
                            } else if (hostname.endsWith(".arpa")) {
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    builder.append("domínio reservado.</li>\n");
                                } else {
                                    builder.append("reserved domain.</li>\n");
                                }
                            } else if (Generic.containsGeneric(domain)) {
                                if (abuseEmail != null) {
                                    emailSet.add(abuseEmail);
                                }
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    builder.append("domínio genérico.</li>\n");
                                } else {
                                    builder.append("generic domain.</li>\n");
                                }
                            } else if (Generic.containsGeneric(hostname)) {
                                emailSet.add("postmaster@" + domain);
                                if (abuseEmail != null) {
                                    emailSet.add(abuseEmail);
                                }
                                if (clientEmail != null) {
                                    emailSet.add(clientEmail);
                                }
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    builder.append("<a target=\"_blank\" href=\"http://spfbl.net/generic/\">rDNS genérico</a>.</li>\n");
                                } else {
                                    builder.append("<a target=\"_blank\" href=\"http://spfbl.net/en/generic/\">generic rDNS</a>.</li>\n");
                                }
                            } else {
                                int loop = 0;
                                String subdominio = hostname;
                                while (loop++ < 32 && subdominio.endsWith(domain)) {
                                    emailSet.add("postmaster@" + subdominio);
                                    int index = subdominio.indexOf('.', 1) + 1;
                                    subdominio = subdominio.substring(index);
                                }
                                if (abuseEmail != null) {
                                    emailSet.add(abuseEmail);
                                }
                                if (clientEmail != null) {
                                    emailSet.add(clientEmail);
                                }
                                if (locale.getLanguage().toLowerCase().equals("pt")) {
                                    builder.append("<a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> válido.</li>\n");
                                } else {
                                    builder.append("valid <a target=\"_blank\" href=\"http://spfbl.net/en/fcrdns/\">FCrDNS</a>.</li>\n");
                                }
                            }
                        } else {
                            if (abuseEmail != null) {
                                emailSet.add(abuseEmail);
                            }
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("<a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> inválido.</li>\n");
                            } else {
                                builder.append("invalid <a target=\"_blank\" href=\"http://spfbl.net/en/fcrdns/\">FCrDNS</a>.</li>\n");
                            }
                        }
                    } while ((hostname = reverseSet.pollFirst()) != null);
                    builder.append("        </ul>\n");
                }
                Distribution distribution;
                if ((distribution = SPF.getDistribution(ip, true)).isNotGreen(ip)) {
                    float probability = distribution.getSpamProbability(ip);
                    boolean blocked = Block.containsCIDR(ip);
                    if (blocked || distribution.isRed(ip)) {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Este IP " + (blocked ? "foi bloqueado" : "está listado") + " por má reputação com " + Core.PERCENT_FORMAT.format(probability) + " de pontos negativos.");
                            buildText(builder, "Para que este IP possa ser removido desta lista, é necessário que o MTA de origem reduza o volume de envios para os destinatários com <a target=\"_blank\" href=\"http://spfbl.net/feedback/\">prefixo de rejeição SPFBL</a> na camada SMTP.");
                        } else {
                            buildText(builder, "This IP " + (blocked ? "was blocked" : "is listed") + " by poor reputation in " + Core.PERCENT_FORMAT.format(probability) + " of negative points.");
                            buildText(builder, "In order for this IP to be removed from this list, it is necessary that the source MTA reduce the sending volume for the recipients with <a target=\"_blank\" href=\"http://spfbl.net/en/feedback/\">SPFBL rejection prefix</a> at SMTP layer.");
                        }
                    } else {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Este IP não está listado neste sistema porém sua reputação está com " + Core.PERCENT_FORMAT.format(probability) + " de pontos negativos.");
                            buildText(builder, "Se esta reputação tiver aumento significativo na quantidade de pontos negativos, este IP será automaticamente listado neste sistema.");
                            buildText(builder, "Para evitar que isto ocorra, reduza os envios com <a target=\"_blank\" href=\"http://spfbl.net/feedback/\">prefixo de rejeição SPFBL</a>.");
                        } else {
                            buildText(builder, "This IP is not listed in this system but its reputation is with " + Core.PERCENT_FORMAT.format(probability) + " of negative points.");
                            buildText(builder, "If this reputation have significant increase in the number of negative points, this IP will automatically be listed in the system.");
                            buildText(builder, "To prevent this from occurring, reduce sending with <a target=\"_blank\" href=\"http://spfbl.net/en/feedback/\">SPFBL rejection prefix</a>.");
                        }
                    }
                } else if (emailSet.isEmpty()) {
                    boolean good = SPF.isGood(ip);
                    boolean blocked = Block.containsCIDR(ip);
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        if (blocked) {
                            buildText(builder, "Este IP foi bloqueado por não ter <a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> válido.");
                        } else if (good) {
                            buildText(builder, "Este IP está com reputação muito boa, porém não tem um <a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> válido.");
                        } else {
                            buildText(builder, "Este IP não está bloqueado porém não tem um <a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> válido.");
                        }
                        if (generic) {
                            buildText(builder, "Não serão aceitos <a target=\"_blank\" href=\"http://spfbl.net/generic/\">rDNS genéricos</a>.");
                        }
                        buildText(builder, "Cadastre um <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> válido para este IP, que aponte para o mesmo IP.");
                        if (blocked) {
                            buildText(builder, "O <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> deve estar sob seu próprio domínio para que a liberação seja efetivada.");
                        } else {
                            buildText(builder, "Qualquer IP com <a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> inválido pode ser bloqueado a qualquer momento.");
                        }
                    } else {
                        if (blocked) {
                            buildText(builder, "This IP has been blocked because have none valid <a target=\"_blank\" href=\"http://spfbl.net/en/fcrdns/\">FCrDNS</a>.");
                        } else if (good) {
                            buildText(builder, "This IP has a very good reputation, but does not have a <a target=\"_blank\" href=\"http://spfbl.net/fcrdns/\">FCrDNS</a> válido.");
                        } else {
                            buildText(builder, "This IP isn't blocked but have none valid <a target=\"_blank\" href=\"http://spfbl.net/en/fcrdns/\">FCrDNS</a>.");
                        }
                        if (generic) {
                            buildText(builder, "<a target=\"_blank\" href=\"http://spfbl.net/en/generic/\"Generic rDNS</a> will not be accepted.");
                        }
                        buildText(builder, "Register a valid <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> for this IP, which points to the same IP.");
                        if (blocked) {
                            buildText(builder, "The <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> must be registered under your own domain for us to be able to delist your system.");
                        } else {
                            buildText(builder, "Any IP with invalid <a target=\"_blank\" href=\"http://spfbl.net/en/fcrdns/\">FCrDNS</a> can be blocked at any time.");
                        }
                    }
                } else if (Block.containsCIDR(ip)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este IP foi bloqueado, porém a reputação dele não está mais ruim.");
                    } else {
                        buildText(builder, "This IP has been blocked, but it's reputation is not bad anymore.");
                    }
                    builder.append("      <hr>\n");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Para que a chave de desbloqueio possa ser enviada, selecione o endereço de e-mail do responsável pelo IP:");
                    } else {
                        buildText(builder, "For the delist key can be sent, select the e-mail address responsible for this IP:");
                    }
                    builder.append("      <form method=\"POST\">\n");
                    builder.append("        <ul>\n");
                    int permittedCount = 0;
                    for (String email : emailSet) {
                        if (Domain.isValidEmail(email)) {
                            if (!Trap.contaisAnything(email)) {
                                if (!NoReply.contains(email, false)) {
                                    permittedCount++;
                                }
                            }
                        }
                    }
                    boolean permittedChecked = false;
                    String email = emailSet.pollFirst();
                    do  {
                        if (!Domain.isValidEmail(email)) {
                            builder.append("          <input type=\"radio\" name=\"identifier\" value=\"");
                            builder.append(email);
                            builder.append("\" disabled>");
                            builder.append("&lt;");
                            builder.append(email);
                            builder.append("&gt; ");
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("inválido.<br>\n");
                            } else {
                                builder.append("invalid.<br>\n");
                            }
                        } else if (Trap.contaisAnything(email)) {
                            builder.append("          <input type=\"radio\" name=\"identifier\" value=\"");
                            builder.append(email);
                            builder.append("\" disabled>");
                            builder.append("&lt;");
                            builder.append(email);
                            builder.append("&gt; ");
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("inexistente.</li><br>\n");
                            } else {
                                builder.append("non-existent.</li><br>\n");
                            }
                        } else if (NoReply.contains(email, false)) {
                            builder.append("          <input type=\"radio\" name=\"identifier\" value=\"");
                            builder.append(email);
                            builder.append("\" disabled>");
                            builder.append("&lt;");
                            builder.append(email);
                            builder.append("&gt; ");
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("não permitido.<br>\n");
                            } else {
                                builder.append("not permitted.<br>\n");
                            }
                        } else {
                            builder.append("          <input type=\"radio\" name=\"identifier\" ");
                            builder.append("onclick=\"document.getElementById('btngo').disabled = false;\" value=\"");
                            builder.append(email);
                            if (permittedChecked) {
                                builder.append("\">");
                            } else if (permittedCount == 1) {
                                builder.append("\" checked>");
                                permittedChecked = true;
                            } else {
                                builder.append("\">");
                            }
                            builder.append("&lt;");
                            builder.append(email);
                            builder.append("&gt; ");
                            if (locale.getLanguage().toLowerCase().equals("pt")) {
                                builder.append("permitido.<br>\n");
                            } else {
                                builder.append("permitted.<br>\n");
                            }
                        }
                    } while ((email = emailSet.pollFirst()) != null);
                    builder.append("        </ul>\n");
                    if (permittedCount == 0) {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "Nenhum e-mail do responsável pelo IP é permitido neste sistema.");
                        } else {
                            buildText(builder, "None of the responsible for IP has e-mail permitted under this system.");
                        }
                    } else {
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            buildText(builder, "O <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> do IP deve estar sob seu próprio domínio. Não aceitamos <a target=\"_blank\" href=\"http://spfbl.net/rdns/\">rDNS</a> com domínios de terceiros.");
                        } else {
                            buildText(builder, "The <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> must be registered under your own domain. We do not accept <a target=\"_blank\" href=\"http://spfbl.net/en/rdns/\">rDNS</a> with third-party domains.");
                        }
                        builder.append("        <div id=\"divcaptcha\">\n");
                        if (Core.hasRecaptchaKeys()) {
                            String recaptchaKeySite = Core.getRecaptchaKeySite();
                            String recaptchaKeySecret = Core.getRecaptchaKeySecret();
                            ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
                            builder.append("        ");
                            builder.append(captcha.createRecaptchaHtml(null, null).replace("\r", ""));
                            // novo reCAPCHA
                //            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
                //            builder.append(recaptchaKeySite);
                //            builder.append("\"></div>\n");
                        }
                        if (locale.getLanguage().toLowerCase().equals("pt")) {
                            builder.append("          <input id=\"btngo\" type=\"submit\" value=\"Solicitar chave de delist\"");
                        } else {
                            builder.append("          <input id=\"btngo\" type=\"submit\" value=\"Request delist key\"");
                        }
                        if (permittedCount == 1) {
                            builder.append(">\n");
                        } else {
                            builder.append(" disabled>\n");
                        }
                        builder.append("        </div>\n");
                        builder.append("      </form>\n");
                    }
                } else if (SPF.isGood(ip)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este IP está com reputação extremamente boa e por isso foi colocado em lista branca.");
                    } else {
                        buildText(builder, "This IP is extremely good reputation and therefore has been whitelisted.");
                    }
                 } else if (Ignore.containsCIDR(ip)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este IP está marcado como serviço essencial e por isso foi colocado em lista branca.");
                    } else {
                        buildText(builder, "This IP is marked as an essential service and therefore has been whitelisted.");
                    }
                } else if (White.containsIP(ip)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este IP está marcado como serviço de mensagem estritamente corporativo e por isso foi colocado em lista branca.");
                        if (Core.hasAbuseEmail()) {
                            buildText(builder, "Se você tiver recebido alguma mensagem promocional deste IP, sem prévia autorização, faça uma denuncia para " + Core.getAbuseEmail() + ".");
                        }
                    } else {
                        buildText(builder, "This IP is marked as strictly corporate message service and therefore has been whitelisted.");
                        if (Core.hasAbuseEmail()) {
                            buildText(builder, "If you received any promotional message from this IP, without permission, make a complaint to " + Core.getAbuseEmail() + ".");
                        }
                    }
                } else if (Block.containsHREF(ip)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este IP está bloqueado para uso de URL.");
                        buildText(builder, "Para que este IP seja removido desta lista, é necessário enviar uma solicitação para " + Core.getAdminEmail() + ".");
                    } else {
                        buildText(builder, "This IP is blocked for URL usage.");
                        buildText(builder, "In order to remove this IP from this list, you must send a request to " + Core.getAdminEmail() + ".");
                    }
               } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Nenhum bloqueio foi encontrado para este IP.");
                        buildText(builder, "Se este IP estiver sendo rejeitado por algum MTA, aguarde a propagação de DNS deste serviço.");
                        buildText(builder, "O tempo de propagação pode levar alguns dias.");
                    } else {
                        buildText(builder, "No block was found for this IP.");
                        buildText(builder, "If this IP is being rejected by some MTA, wait for the DNS propagation of this service.");
                        buildText(builder, "The propagation time can take a few days.");
                    }
                }
            }
        } else if (Domain.isHostname(query)) {
            Distribution distribution;
            String domain = Domain.normalizeHostname(query, true);
            if (domain.equals(".test") || domain.equals(".invalid")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este é um domínio reservado e por este motivo não é abordado nesta lista.");
                } else {
                    buildText(builder, "This is a reserved domain and for this reason is not addressed in this list.");
                }
            } else if (Generic.containsDynamic(domain)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este hostname está listado por referência a um padrão de <a target=\"_blank\" href=\"http://spfbl.net/dynamic/\">IP dinâmico</a>.");
                    if (Core.hasAdminEmail()) {
                        buildText(builder, "Se você discorda que se trata de hostname com padrão de <a target=\"_blank\" href=\"http://spfbl.net/dynamic/\">IP dinâmico</a>, entre em contato conosco através do e-mail " + Core.getAdminEmail() + ".");
                    }
                } else {
                    buildText(builder, "This hostname is listed by reference to a <a target=\"_blank\" href=\"http://spfbl.net/en/dynamic/\">dynamic IP</a> pattern.");
                    if (Core.hasAdminEmail()) {
                        buildText(builder, "If you disagree that this is hostname with <a target=\"_blank\" href=\"http://spfbl.net/en/dynamic/\">dynamic IP</a> pattern, contact us by email " + Core.getAdminEmail() + ".");
                    }
                }
            } else if ((distribution = SPF.getDistribution(domain, true)).isNotGreen(domain)) {
                float probability = distribution.getSpamProbability(domain);
                boolean blocked = Block.containsDomain(domain, false);
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este domínio " + (blocked ? "foi bloqueado" : "está listado") + " por má reputação com " + Core.PERCENT_FORMAT.format(probability) + " de pontos negativos do volume total de envio.");
                    buildText(builder, "Para que este domínio possa ser removido desta lista, é necessário que todos os MTAs de origem reduzam o volume de envios para os destinatários com <a target=\"_blank\" href=\"http://spfbl.net/feedback/\">prefixo de rejeição SPFBL</a> na camada SMTP.");
                } else {
                    buildText(builder, "This domain " + (blocked ? "was blocked" : "is listed") + " by poor reputation in " + Core.PERCENT_FORMAT.format(probability) + " of negative points of total sending the recipients with <a target=\"_blank\" href=\"http://spfbl.net/en/feedback/\">SPFBL rejection prefix</a> at SMTP layer.");
                }
            } else if (Block.containsDomain(domain, true)) {
                if (Reverse.isInexistentDomain(domain)) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este domínio está listado por não existir oficialmente.");
                    } else {
                        buildText(builder, "This domain is listed because it does not exist officially.");
                    }
                } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        buildText(builder, "Este domínio está listado por bloqueio manual.");
                        String ip = SPF.getUniqueIP(domain);
                        String url = Core.getURL(locale, ip);
                        if (ip != null && url != null && Block.containsCIDR(ip)) {
                            buildText(builder, "Para que este domínio seja removido desta lista, é necessário desbloquear seu respectivo IP: <a href='" + url + "'>" + ip + "</a>");
                        } else if (Core.hasAdminEmail()) {
                            buildText(builder, "Para que este domínio seja removido desta lista, é necessário enviar uma solicitação para " + Core.getAdminEmail() + ".");
                        }
                    } else {
                        buildText(builder, "This domain is listed by manual block.");
                        String ip = SPF.getUniqueIP(domain);
                        String url = Core.getURL(locale, ip);
                        if (ip != null && url != null && Block.containsCIDR(ip)) {
                            buildText(builder, "In order for this domain to be removed from this list, it is necessary to unblock its respective IP: <a href='" + url + "'>" + ip + "</a>");
                        } else if (Core.hasAdminEmail()) {
                            buildText(builder, "In order to remove this domain from this list, you must send a request to " + Core.getAdminEmail() + ".");
                        }
                    }
                }
            } else if (SPF.isGood(domain)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este domínio está com reputação extremamente boa e por isso foi colocado em lista branca.");
                } else {
                    buildText(builder, "This domain is extremely good reputation and therefore has been whitelisted.");
                }
            } else if (Ignore.containsHost(domain)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este domínio está marcado como serviço essencial e por isso foi colocado em lista branca.");
                } else {
                    buildText(builder, "This domain is marked as an essential service and therefore has been whitelisted.");
                }
            } else if (White.containsDomain(domain)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este domínio está marcado como serviço de mensagem estritamente corporativo e por isso foi colocado em lista branca.");
                    if (Core.hasAbuseEmail()) {
                        buildText(builder, "Se você tiver recebido alguma mensagem promocional deste domínio, sem prévia autorização, faça uma denuncia para " + Core.getAbuseEmail() + ".");
                    }
                } else {
                    buildText(builder, "This domain is marked as strictly corporate message service and therefore has been whitelisted.");
                    if (Core.hasAbuseEmail()) {
                        buildText(builder, "If you received any promotional message from this domain, without permission, make a complaint to " + Core.getAbuseEmail() + ".");
                    }
                }
            } else if (Block.containsHREF(domain)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Este domínio está bloqueado para uso em URL.");
                    if (Core.hasAdminEmail()) {
                        buildText(builder, "Para que este domínio seja removido desta lista, é necessário enviar uma solicitação para " + Core.getAdminEmail() + ".");
                    }
                } else {
                    buildText(builder, "This domain is blocked for URL usage.");
                    if (Core.hasAdminEmail()) {
                        buildText(builder, "In order to remove this domain from this list, you must send a request to " + Core.getAdminEmail() + ".");
                    }
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Nenhum bloqueio foi encontrado para este domínio.");
                } else {
                    buildText(builder, "No block was found for this domain.");
                }
            }
        }
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getRedirectHTML(Locale locale, String url) {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        buildHead(builder, null, url, 0);
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static String getControlPanel(
            Locale locale,
            Query query,
            long time
            ) {
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(time);
        StringBuilder builder = new StringBuilder();
//        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        builder.append("  <head>\n");
        builder.append("    <meta charset=\"UTF-8\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("    <title>Painel de controle do SPFBL</title>\n");
        } else {
            builder.append("    <title>SPFBL control panel</title>\n");
        }
        // Styled page.
        builder.append("    <style type=\"text/css\">\n");
        builder.append("      body {");
        builder.append("        background: #b4b9d2;\n");
        builder.append("      }\n");
        builder.append("      .button {\n");
        builder.append("          background-color: #4CAF50;\n");
        builder.append("          border: none;\n");
        builder.append("          color: white;\n");
        builder.append("          padding: 16px 32px;\n");
        builder.append("          text-align: center;\n");
        builder.append("          text-decoration: none;\n");
        builder.append("          display: inline-block;\n");
        builder.append("          font-size: 16px;\n");
        builder.append("          margin: 4px 2px;\n");
        builder.append("          -webkit-transition-duration: 0.4s;\n");
        builder.append("          transition-duration: 0.4s;\n");
        builder.append("          cursor: pointer;\n");
        builder.append("      }\n");
        builder.append("      .white {\n");
        builder.append("          background-color: white; \n");
        builder.append("          color: black; \n");
        builder.append("          border: 2px solid #4CAF50;\n");
        builder.append("          font-weight: bold;\n");
        builder.append("      }\n");
        builder.append("      .white:hover {\n");
        builder.append("          background-color: #4CAF50;\n");
        builder.append("          color: white;\n");
        builder.append("      }\n");
        builder.append("      .block {\n");
        builder.append("          background-color: white; \n");
        builder.append("          color: black; \n");
        builder.append("          border: 2px solid #f44336;\n");
        builder.append("          font-weight: bold;\n");
        builder.append("      }\n");
        builder.append("      .block:hover {\n");
        builder.append("          background-color: #f44336;\n");
        builder.append("          color: white;\n");
        builder.append("      }\n");
        builder.append("      .recipient {\n");
        builder.append("          background-color: white; \n");
        builder.append("          color: black; \n");
        builder.append("          border: 2px solid #555555;\n");
        builder.append("          font-weight: bold;\n");
        builder.append("      }\n");
        builder.append("      .recipient:hover {\n");
        builder.append("          background-color: #555555;\n");
        builder.append("          color: white;\n");
        builder.append("      }\n");
        builder.append("    </style>\n");
        builder.append("  </head>\n");
        // Body.
        builder.append("  <body>\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("    <b>Recepção:</b> ");
        } else {
            builder.append("    <b>Reception:</b> ");
        }
        builder.append(dateFormat.format(calendar.getTime()));
        builder.append("<br>\n");
        String sender = query.getSenderSimplified(false, false);
        if (sender == null) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("    <b>Remetente:</b> MAILER-DAEMON");
            } else {
                builder.append("    <b>Sender:</b> MAILER-DAEMON");
            }
        } else if (query.getQualifierName().equals("PASS")) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("    <b>Remetente autêntico:</b> ");
            } else {
                builder.append("    <b>Genuine sender:</b> ");
            }
            builder.append(sender);
        } else if (query.getQualifierName().equals("FAIL")) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("    <b>Remetente falso:</b> ");
            } else {
                builder.append("    <b>False sender:</b> ");
            }
            builder.append(sender);
        } else {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("    <b>Remetente suspeito:</b> ");
            } else {
                builder.append("    <b>Suspect sender:</b> ");
            }
            builder.append(sender);
        }
        builder.append("<br>\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("    <b>Recebe por:</b> ");
        } else {
            builder.append("    <b>Receives for:</b> ");
        }
        String validator = query.getValidator(true);
        Situation situationWhite = query.getSenderWhiteSituation();
        Situation situationBlock = query.getSenderBlockSituation();
        try {
            TreeSet<String> mxDomainSet = query.getSenderMXDomainSet();
            if (mxDomainSet.isEmpty()) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("nenhum sistema");
                } else {
                    builder.append("no system");
                }
            } else {
                builder.append(mxDomainSet);
            }
        } catch (NameNotFoundException ex) {
            validator = null;
            situationWhite = query.getOriginWhiteSituation();
            situationBlock = query.getOriginBlockSituation();
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("domínio inexistente");
            } else {
                builder.append("non-existent domain");
            }
        } catch (NamingException ex) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("erro ao tentar consultar");
            } else {
                builder.append("error when trying to query");
            }
        }
        builder.append("<br>\n");
        URL unsubscribe = query.getUnsubscribeURL();
        if (unsubscribe == null) {
            builder.append("    <br>\n");
        } else {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("     <b>Cancelar inscrição:</b> ");
            } else {
                builder.append("     <b>List unsubscribe:</b> ");
            }
            builder.append("<a target=\"_blank\" href=\"");
            builder.append(unsubscribe);
            builder.append("\">");
            builder.append(unsubscribe.getHost());
            builder.append(unsubscribe.getPath());
            builder.append("</a><br>\n");
        }
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            builder.append("    <b>Politica vigente:</b> ");
        } else {
            builder.append("    <b>Current policy:</b> ");
        }
        String recipient = query.getRecipient();
        Long trapTime = query.getTrapTime();
        boolean blocked = false;
        if (trapTime == null && situationWhite == Situation.SAME) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("entrega prioritária na mesma situação, exceto malware");
            } else {
                builder.append("priority delivery of ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" in the same situation, except malware");
            }
        } else if (trapTime == null && situationWhite == Situation.AUTHENTIC) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("entrega prioritária de ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" quando autêntico, exceto malware");
            } else {
                builder.append("priority delivery of ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" when authentic, except malware");
            }
            if (query.isBlock()) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append(", porém bloqueado para outras situações");
                } else {
                    builder.append(", however blocked to other situations");
                }
            }
        } else if (trapTime == null && situationWhite == Situation.ZONE) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("entrega prioritária de ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" quando disparado por ");
            } else {
                builder.append("priority delivery of ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" when shot by ");
            }
            builder.append(validator);
            if (query.isBlock()) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append(", porém bloqueado para outras situações");
                } else {
                    builder.append(", however blocked to other situations");
                }
            }
        } else if (trapTime == null && situationWhite == Situation.IP) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("entrega prioritária de ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" when shot by IP ");
            } else {
                builder.append("priority delivery of ");
                builder.append(query.getSenderSimplified(false, true));
                builder.append(" when coming from the IP ");
            }
            builder.append(validator);
            if (query.isBlock()) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append(", porém bloqueado para outras situações");
                } else {
                    builder.append(", however blocked to other situations");
                }
            }
        } else if (trapTime == null && situationWhite == Situation.ORIGIN) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("entrega prioritária pela mesma origem");
            } else {
                builder.append("priority delivery the same origin");
            }
        } else if (situationBlock == Situation.DOMAIN) {
            blocked = true;
            String domain = query.getSenderSimplified(true, false);
            if (domain == null) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("bloquear na mesma situação");
                } else {
                    builder.append("block in the same situation");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("bloquear ");
                    builder.append(domain);
                    builder.append(" em qualquer situação");
                } else {
                    builder.append("block ");
                    builder.append(domain);
                    builder.append(" in any situation");
                }
            }
        } else if (situationBlock == Situation.ALL) {
            blocked = true;
            String domain = query.getOriginDomain(false);
            if (domain == null) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("bloquear na mesma situação");
                } else {
                    builder.append("block in the same situation");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("bloquear ");
                    builder.append(domain);
                    builder.append(" em qualquer situação");
                } else {
                    builder.append("block ");
                    builder.append(domain);
                    builder.append(" in any situation");
                }
            }
        } else if (situationBlock == Situation.SAME) {
            blocked = true;
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("bloquear na mesma situação");
            } else {
                builder.append("block in the same situation");
            }
        } else if ((situationBlock == Situation.ZONE || situationBlock == Situation.IP) && !query.getQualifierName().equals("PASS")) {
            blocked = true;
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("bloquear ");
                builder.append(query.getSenderDomain(false));
                builder.append(" quando não for autêntico");
            } else {
                builder.append("block ");
                builder.append(query.getSenderDomain(false));
                builder.append(" when not authentic");
            }
        } else if (situationBlock == Situation.ORIGIN) {
            blocked = true;
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("bloquear quando disparado pela mesma origem");
            } else {
                builder.append("block when shot by the same source");
            }
        } else if (query.isFail()) {
            blocked = true;
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("rejeitar entrega por falsificação");
            } else {
                builder.append("reject delivery of forgery");
            }
        } else if (trapTime != null) {
            if (System.currentTimeMillis() > trapTime) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("descartar mensagem por armadilha");
                } else {
                    builder.append("discard message by spamtrap");
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("rejeitar entrega por destinatário inexistente");
                } else {
                    builder.append("reject delivery by inexistent recipient");
                }
            }
        } else if (query.hasTokenRed()) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("marcar como suspeita e entregar, sem considerar o conteúdo");
            } else {
                builder.append("flag as suspected and deliver, regardless of content");
            }
        } else if (query.isSoftfail() || query.hasYellow() || query.hasClusterYellow()) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("atrasar entrega na mesma situação, sem considerar o conteúdo");
            } else {
                builder.append("delay delivery in the same situation, regardless of content");
            }
        } else {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("aceitar entrega na mesma situação, sem considerar o conteúdo");
            } else {
                builder.append("accept delivery in the same situation, regardless of content");
            }
        }
        builder.append(".<br>\n");
        builder.append("    <form method=\"POST\">\n");
        if (validator == null) {
            if (situationWhite != Situation.ORIGIN) {
                builder.append("      <button type=\"submit\" class=\"white\" name=\"POLICY\" value=\"WHITE_ORIGIN\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega prioritária quando for da mesma origem\n");
                } else {
                    builder.append("Priority delivery when the same origin\n");
                }
                builder.append("</button>\n");
            }
            if (situationBlock == Situation.ORIGIN && !query.isOriginBlock()) {
                String domain = query.getOriginDomain(false);
                if (domain == null) {
                    String ip = query.getIP();
                    builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_ORIGIN\">");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Bloquear ");
                        builder.append(ip);
                        builder.append(" em qualquer situação");
                    } else {
                        builder.append("Block ");
                        builder.append(ip);
                        builder.append(" in any situation");
                    }
                    builder.append("</button>\n");
                } else {
                    builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_ORIGIN\">");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Bloquear ");
                        builder.append(domain);
                        builder.append(" em qualquer situação");
                    } else {
                        builder.append("Block ");
                        builder.append(domain);
                        builder.append(" in any situation");
                    }
                    builder.append("</button>\n");
                }
            }
//            if (situationWhite != Situation.NONE || situationBlock != Situation.ALL) {
//                if (situationBlock != Situation.ORIGIN) {
//                    builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_ORIGIN\">");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("Bloquear se for da mesma origem");
//                    } else {
//                        builder.append("Block if the same origin");
//                    }
//                    builder.append("</button>\n");
//                }
//                String domain = query.getOriginDomain(false);
//                if (domain != null) {
//                    builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_ALL\">");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("Bloquear ");
//                        builder.append(domain);
//                        builder.append(" em qualquer situação");
//                    } else {
//                        builder.append("Block ");
//                        builder.append(domain);
//                        builder.append(" in any situation");
//                    }
//                    builder.append("</button>\n");
//                }
//            }
        } else if (validator.equals("PASS")) {
            if (situationWhite != Situation.AUTHENTIC) {
                builder.append("      <button type=\"submit\" class=\"white\" name=\"POLICY\" value=\"WHITE_AUTHENTIC\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega prioritária quando autêntico\n");
                } else {
                    builder.append("Priority delivery when authentic\n");
                }
                builder.append("</button>\n");
            }
        } else if (Subnet.isValidIP(validator)) {
            if (situationWhite != Situation.IP) {
                builder.append("      <button type=\"submit\" class=\"white\" name=\"POLICY\" value=\"WHITE_IP\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega prioritária quando disparado pelo IP ");
                } else {
                    builder.append("Priority delivery when shot by IP ");
                }
                builder.append(validator);
                builder.append("</button>\n");
            }
            if (situationBlock != Situation.IP && situationBlock != Situation.DOMAIN) {
                builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_IP\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Bloquear ");
                    builder.append(query.getSenderDomain(false));
                    builder.append(" quando não for autêntico");
                } else {
                    builder.append("Block ");
                    builder.append(query.getSenderDomain(false));
                    builder.append(" when not authentic");
                }
                builder.append("</button>\n");
            }
        } else if (Domain.isHostname(validator)) {
            if (situationWhite != Situation.ZONE) {
                builder.append("      <button type=\"submit\" class=\"white\" name=\"POLICY\" value=\"WHITE_ZONE\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega prioritária quando disparado por ");
                } else {
                    builder.append("Priority delivery when shot by ");
                }
                builder.append(validator);
                builder.append("</button>\n");
            }
            if (situationBlock != Situation.ZONE && situationBlock != Situation.DOMAIN) {
                builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_ZONE\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Bloquear ");
                    builder.append(query.getSenderDomain(false));
                    builder.append(" quando não for autêntico");
                } else {
                    builder.append("Block ");
                    builder.append(query.getSenderDomain(false));
                    builder.append(" when not authentic");
                }
                builder.append("</button>\n");
            }
        }
        if (situationBlock != Situation.DOMAIN && validator != null) {
            builder.append("      <button type=\"submit\" class=\"block\" name=\"POLICY\" value=\"BLOCK_DOMAIN\">");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("Bloquear ");
                builder.append(query.getSenderSimplified(true, false));
                builder.append(" em qualquer situação");
            } else {
                builder.append("Block ");
                builder.append(query.getSenderSimplified(true, false));
                builder.append(" in any situation");
            }
            builder.append("</button>\n");
        }
        if (!blocked && recipient != null && trapTime != null && query.getUser().isPostmaster()) {
            builder.append("      <button type=\"submit\" class=\"recipient\" name=\"POLICY\" value=\"WHITE_RECIPIENT\">");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("Tornar ");
                builder.append(recipient);
                builder.append(" existente");
            } else {
                builder.append("Make ");
                builder.append(recipient);
                builder.append(" existent");
            }
            builder.append("</button>\n");
        }
        builder.append("    </form>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }
    
    private static void buildQueryRow(
            Locale locale,
            StringBuilder builder,
            DateFormat dateFormat,
            GregorianCalendar calendar,
            long time,
            User.Query query,
            boolean highlight
    ) {
        if (query != null) {
            calendar.setTimeInMillis(time);
            String ip = query.getIP();
            String hostname = query.getValidHostname();
            String sender = query.getSender();
            String from = query.getFrom();
            String replyto = query.getReplyTo();
            String subject = query.getSubject();
            String malware = query.getMalware();
            String recipient = query.getRecipient();
            String result = query.getResult();
            builder.append("        <tr id=\"");
            builder.append(time);
            builder.append("\"");
            if (highlight) {
                builder.append(" class=\"highlight\"");
            } else {
                builder.append(" class=\"click\"");
            }
            builder.append(" onclick=\"view('");
            builder.append(time);
            builder.append("')\">\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("          <td style=\"width:120px;\">");
            } else {
                builder.append("          <td style=\"width:160px;\">");
            }
            builder.append(dateFormat.format(calendar.getTime()));
            builder.append("<br>");
            builder.append(query.getClient());
            builder.append("</td>\n");
            builder.append("          <td>");
            if (hostname == null) {
                String helo = query.getHELO();
                if (helo == null) {
                    builder.append(ip);
                } else if (Subnet.isValidIP(helo)) {
                    builder.append(ip);
                } else {
                    builder.append(ip);
                    builder.append("<br>");
                    builder.append("<strike>");
                    builder.append(helo);
                    builder.append("</strike>");
                }
            } else if (Generic.containsDynamicDomain(hostname)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><i>Dinâmico</i></small>");
                } else {
                    builder.append("<small><i>Dynamic</i></small>");
                }
                builder.append("<br>");
                builder.append(hostname);
            } else if (Generic.containsGenericDomain(hostname)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><i>Genérico</i></small>");
                } else {
                    builder.append("<small><i>Generic</i></small>");
                }
                builder.append("<br>");
                builder.append(hostname);
            } else if (Provider.containsDomain(hostname)) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><i>Provedor</i></small>");
                } else {
                    builder.append("<small><i>Provider</i></small>");
                }
                builder.append("<br>");
                builder.append(hostname);
            } else {
                builder.append(hostname);
            }
            builder.append("</td>\n");
            TreeSet<String> senderSet = new TreeSet<String>();
            builder.append("          <td>");
            if (sender == null) {
                builder.append("MAILER-DAEMON");
            } else {
                senderSet.add(sender);
                String qualifier = query.getQualifierName();
                if (qualifier.equals("PASS")) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Autêntico</i></small>");
                    } else {
                        builder.append("<small><i>Genuine</i></small>");
                    }
                } else if (qualifier.equals("FAIL")) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Falso</i></small>");
                    } else {
                        builder.append("<small><i>False</i></small>");
                    }
                } else if (qualifier.equals("SOFTFAIL")) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Pode ser falso</i></small>");
                    } else {
                        builder.append("<small><i>May be false</i></small>");
                    }
                } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Pode ser autêntico</i></small>");
                    } else {
                        builder.append("<small><i>May be genuine</i></small>");
                    }
                }
                builder.append("<br>");
                builder.append(sender);
            }
            boolean lineSeparator = false;
            if (from != null && !senderSet.contains(from)) {
                senderSet.add(from);
                builder.append("<hr style=\"height:0px;visibility:hidden;margin-bottom:0px;\">");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><b>De:</b> ");
                } else {
                    builder.append("<small><b>From:</b> ");
                }
                builder.append(from);
                builder.append("</small>");
                lineSeparator = true;
            }
            if (replyto != null && !senderSet.contains(replyto)) {
                senderSet.add(replyto);
                if (lineSeparator) {
                    builder.append("<br>");
                } else {
                    builder.append("<hr style=\"height:0px;visibility:hidden;margin-bottom:0px;\">");
                }
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><b>Responder para:</b> ");
                } else {
                    builder.append("<small><b>Reply to:</b> ");
                }
                builder.append(replyto);
                builder.append("</small>");
            }
            builder.append("</td>\n");
            builder.append("          <td>");
            if (subject != null) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><b>Assunto:</b> ");
                } else {
                    builder.append("<small><b>Subject:</b> ");
                }
                builder.append(subject);
                builder.append("</small>");
                builder.append("<hr style=\"height:0px;visibility:hidden;margin-bottom:0px;\">");
            }
            if (malware == null) {
                TreeSet<String> linkSet = query.getLinkSet();
                if (linkSet == null) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Corpo não verificado</i></small>");
                    } else {
                        builder.append("<small><i>Body not verified</i></small>");
                    }
                } else if (linkSet.isEmpty()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("<small><i>Sem links</i></small>");
                    } else {
                        builder.append("<small><i>No links</i></small>");
                    }
                } else {
                    String link = linkSet.pollFirst();
                    if (query.isLinkBlocked(link)) {
                        builder.append("<font color=\"DarkRed\"><b>");
                        builder.append(link);
                        builder.append("</b></font>");
                    } else {
                        builder.append(link);
                    }
                    while (!linkSet.isEmpty()) {
                        builder.append("<br>");
                        link = linkSet.pollFirst();
                        if (query.isLinkBlocked(link)) {
                            builder.append("<font color=\"DarkRed\"><b>");
                            builder.append(link);
                            builder.append("</b></font>");
                        } else {
                            builder.append(link);
                        }
                    }
                }
            } else {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("<small><i>Malware encontrado</i></small>");
                } else {
                    builder.append("<small><i>Malware found</i></small>");
                }
                if (!malware.equals("FOUND")) {
                    builder.append("<br>");
                    builder.append("<font color=\"DarkRed\"><b>");
                    builder.append(malware);
                    builder.append("</b></font>");
                }
            }
            builder.append("</td>\n");
            builder.append("          <td>");
            if (result.equals("REJECT")) {
                if (query.hasMalware()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por segurança");
                    } else {
                        builder.append("Rejected by security");
                    }
                } else if (query.hasLinkBlocked()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por conteúdo indesejado");
                    } else {
                        builder.append("Rejected by unwanted content");
                    }
                } else if (!query.hasSubject()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por origem suspeita");
                    } else {
                        builder.append("Rejected by suspect origin");
                    }
                } else if (!query.hasMailFrom() && !query.hasHeaderFrom()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por ausencia de remetente");
                    } else {
                        builder.append("Rejected by absence of sender");
                    }
                } else if (!query.hasMessageID()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por ausência de idenficação");
                    } else {
                        builder.append("Rejected for lack of identification");
                    }
                } else if (query.hasMiscellaneousSymbols()) {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitado por símbolos suspeitos");
                    } else {
                        builder.append("Rejected by suspicious symbols");
                    }
                } else {
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("Rejeitada por conteúdo suspeito");
                    } else {
                        builder.append("Rejected by suspicious content");
                    }
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("BLOCK") || result.equals("BLOCKED")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por bloqueio");
                } else {
                    builder.append("Rejected by blocking");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("FAIL") || result.equals("FAILED")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por falsidade");
                } else {
                    builder.append("Rejected by falseness");
                }
            } else if (result.equals("INVALID")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por origem inválida");
                } else {
                    builder.append("Rejected by invalid source");
                }
            } else if (result.equals("GREYLIST")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Atrasada por greylisting");
                } else {
                    builder.append("Delayed by greylisting");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("SPAMTRAP") || result.equals("TRAP")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Descartado pela armadilha");
                } else {
                    builder.append("Discarded by spamtrap");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("INEXISTENT")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por inexistência");
                } else {
                    builder.append("Rejected by non-existence");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("WHITE")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega prioritária");
                } else {
                    builder.append("Priority delivery");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("ACCEPT")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega aceita");
                } else {
                    builder.append("Accepted for delivery");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("FLAG")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Marcada como suspeita");
                } else {
                    builder.append("Marked as suspect");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("HOLD")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Entrega retida");
                } else {
                    builder.append("Delivery retained");
                }
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            } else if (result.equals("NXDOMAIN")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por domínio inexistente");
                } else {
                    builder.append("Rejected by non-existent domain");
                }
            } else if (result.equals("NXSENDER")) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("Rejeitada por remetente inexistente");
                } else {
                    builder.append("Rejected by non-existent sender");
                }
            } else {
                builder.append(result);
                if (recipient != null) {
                    builder.append("<br>");
                    builder.append(recipient);
                }
            }
            builder.append("</td>\n");
            builder.append("        </tr>\n");
        }
    }
    
//    private static String getControlPanel(
//            Locale locale,
//            User user,
//            Long begin,
//            String filter
//            ) {
//        StringBuilder builder = new StringBuilder();
//        if (begin == null && filter == null) {
////            builder.append("<!DOCTYPE html>\n");
//            builder.append("<html lang=\"");
//            builder.append(locale.getLanguage());
//            builder.append("\">\n");
//            builder.append("  <head>\n");
//            builder.append("    <meta charset=\"UTF-8\">\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("    <title>Painel de controle do SPFBL</title>\n");
//            } else {
//                builder.append("    <title>SPFBL control panel</title>\n");
//            }
//            // Styled page.
//            builder.append("    <style type=\"text/css\">\n");
//            builder.append("      body {\n");
//            builder.append("        margin:180px 0px 30px 0px;\n");
//            builder.append("        background:lightgray;\n");
//            builder.append("      }\n");
//            builder.append("      iframe {\n");
//            builder.append("        border-width: 0px 0px 0px 0px;\n");
//            builder.append("        width:100%;\n");
//            builder.append("        height:150px;\n");
//            builder.append("      }\n");
//            builder.append("      .header {\n");
//            builder.append("        background-color:lightgray;\n");
//            builder.append("        border-width: 0px 0px 0px 0px;\n");
//            builder.append("        position:fixed;\n");
//            builder.append("        top:0px;\n");
//            builder.append("        margin:auto;\n");
//            builder.append("        z-index:1;\n");
//            builder.append("        width:100%;\n");
//            builder.append("        height:180px;\n");
//            builder.append("      }\n");
//            builder.append("      .bottom {\n");
//            builder.append("        background-color:lightgray;\n");
//            builder.append("        border-width: 0px 0px 0px 0px;\n");
//            builder.append("        position:fixed;\n");
//            builder.append("        bottom:0px;\n");
//            builder.append("        margin:auto;\n");
//            builder.append("        z-index:1;\n");
//            builder.append("        width:100%;\n");
//            builder.append("        height:30px;\n");
//            builder.append("      }\n");
//            builder.append("      .button {\n");
//            builder.append("          background-color: #4CAF50;\n");
//            builder.append("          border: none;\n");
//            builder.append("          color: white;\n");
//            builder.append("          padding: 16px 32px;\n");
//            builder.append("          text-align: center;\n");
//            builder.append("          text-decoration: none;\n");
//            builder.append("          display: inline-block;\n");
//            builder.append("          font-size: 16px;\n");
//            builder.append("          margin: 4px 2px;\n");
//            builder.append("          -webkit-transition-duration: 0.4s;\n");
//            builder.append("          transition-duration: 0.4s;\n");
//            builder.append("          cursor: pointer;\n");
//            builder.append("      }\n");
//            builder.append("      .sender {\n");
//            builder.append("          background-color: white; \n");
//            builder.append("          color: black; \n");
//            builder.append("          border: 2px solid #008CBA;\n");
//            builder.append("          width: 100%;\n");
//            builder.append("          word-wrap: break-word;\n");
//            builder.append("      }\n");
//            builder.append("      .sender:hover {\n");
//            builder.append("          background-color: #008CBA;\n");
//            builder.append("          color: white;\n");
//            builder.append("      }\n");
//            builder.append("      .highlight {\n");
//            builder.append("        background: #b4b9d2;\n");
//            builder.append("        color:black;\n");
//            builder.append("        border-top: 1px solid #22262e;\n");
//            builder.append("        border-bottom: 1px solid #22262e;\n");
//            builder.append("      }\n");
//            builder.append("      .highlight:nth-child(odd) td {\n");
//            builder.append("        background: #b4b9d2;\n");
//            builder.append("      }\n");
//            builder.append("      .click {\n");
//            builder.append("        cursor:pointer;\n");
//            builder.append("        cursor:hand;\n");
//            builder.append("      }\n");
//            builder.append("      table {\n");
//            builder.append("        background: white;\n");
//            builder.append("        table-layout:fixed;\n");
//            builder.append("        border-collapse: collapse;\n");
//            builder.append("        word-wrap:break-word;\n");
//            builder.append("        border-radius:3px;\n");
//            builder.append("        border-collapse: collapse;\n");
//            builder.append("        margin: auto;\n");
//            builder.append("        padding:2px;\n");
//            builder.append("        width: 100%;\n");
//            builder.append("        box-shadow: 0 5px 10px rgba(0, 0, 0, 0.1);\n");
//            builder.append("        animation: float 5s infinite;\n");
//            builder.append("      }\n");
//            builder.append("      th {\n");
//            builder.append("        color:#FFFFFF;;\n");
//            builder.append("        background:#1b1e24;\n");
//            builder.append("        border-bottom:4px solid #9ea7af;\n");
//            builder.append("        border-right: 0px;\n");
//            builder.append("        font-size:16px;\n");
//            builder.append("        font-weight: bold;\n");
//            builder.append("        padding:4px;\n");
//            builder.append("        text-align:left;\n");
//            builder.append("        text-shadow: 0 1px 1px rgba(0, 0, 0, 0.1);\n");
//            builder.append("        vertical-align:middle;\n");
//            builder.append("        height:30px;\n");
//            builder.append("      }\n");
//            builder.append("      tr {\n");
//            builder.append("        border-top: 1px solid #C1C3D1;\n");
//            builder.append("        border-bottom-: 1px solid #C1C3D1;\n");
//            builder.append("        font-size:16px;\n");
//            builder.append("        font-weight:normal;\n");
//            builder.append("        text-shadow: 0 1px 1px rgba(256, 256, 256, 0.1);\n");
//            builder.append("      }\n");
//            builder.append("      tr:nth-child(odd) td {\n");
//            builder.append("        background:#EBEBEB;\n");
//            builder.append("      }\n");
//            builder.append("      td {\n");
//            builder.append("        padding:2px;\n");
//            builder.append("        vertical-align:middle;\n");
//            builder.append("        font-size:16px;\n");
//            builder.append("        text-shadow: -1px -1px 1px rgba(0, 0, 0, 0.1);\n");
//            builder.append("        border-right: 1px solid #C1C3D1;\n");
//            builder.append("      }\n");
//            builder.append("      input[type=text], select {\n");
//            builder.append("        width: 400px;\n");
//            builder.append("        padding: 0px 4px;\n");
//            builder.append("        margin: 1px 0;\n");
//            builder.append("        display: inline-block;\n");
//            builder.append("        background: #b4b9d2;\n");
//            builder.append("        border: 1px solid #ccc;\n");
//            builder.append("        border-radius: 4px;\n");
//            builder.append("        box-sizing: border-box;\n");
//            builder.append("      }\n");
//            builder.append("    </style>\n");
//            // JavaScript functions.
//            TreeSet<Long> queryKeySet = user.getQueryKeySet(null, null);
//            builder.append("    <script type=\"text/javascript\" src=\"https://ajax.googleapis.com/ajax/libs/jquery/2.1.3/jquery.min.js\"></script>\n");
//            builder.append("    <script type=\"text/javascript\">\n");
//            builder.append("      window.onbeforeunload = function () {\n");
//            builder.append("        document.getElementById('filterField').value = '';\n");
//            builder.append("        window.scrollTo(0, 0);\n");
//            builder.append("      }\n");
//            builder.append("      var last = ");
//            if (queryKeySet.isEmpty()) {
//                builder.append(0);
//            } else {
//                builder.append(queryKeySet.last());
//            }
//            builder.append(";\n");
//            builder.append("      var filterText = '';\n");
//            builder.append("      function view(query) {\n");
//            builder.append("        if (query == undefined || query == 0) {\n");
//            builder.append("          var viewer = document.getElementById('viewer');\n");
//            builder.append("          viewer.src = 'about:blank';\n");
//            builder.append("          last = 0;\n");
//            builder.append("        } else if (last != query) {\n");
//            builder.append("          var viewer = document.getElementById('viewer');\n");
//            builder.append("          viewer.addEventListener('load', function() {\n");
//            builder.append("            if (document.getElementById(last)) {\n");
//            builder.append("              document.getElementById(last).className = 'tr';\n");
//            builder.append("              document.getElementById(last).className = 'click';\n");
//            builder.append("            }\n");
//            builder.append("            document.getElementById(query).className = 'highlight';\n");
//            builder.append("            last = query;\n");
//            builder.append("          });\n");
//            builder.append("          viewer.src = '");
//            builder.append(Core.getURL());
//            builder.append("' + query;\n");
//            builder.append("        }\n");
//            builder.append("      }\n");
//            builder.append("      function more(query) {\n");
//            builder.append("        var rowMore = document.getElementById('rowMore');\n");
//            builder.append("        rowMore.onclick = '';\n");
//            builder.append("        rowMore.className = 'tr';\n");
//            builder.append("        var columnMore = document.getElementById('columnMore');\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("        columnMore.innerHTML = 'carregando mais registros';\n");
//            } else {
//                builder.append("        columnMore.innerHTML = 'loading more records';\n");
//            }
//            builder.append("        $.post(\n");
//            builder.append("          '");
//            builder.append(Core.getURL());
//            builder.append(user.getEmail());
//            builder.append("',\n");
//            builder.append("          {filter:filterText,begin:query},\n");
//            builder.append("          function(data, status) {\n");
//            builder.append("            if (status == 'success') {\n");
//            builder.append("              rowMore.parentNode.removeChild(rowMore);\n");
//            builder.append("              $('#tableBody').append(data);\n");
//            builder.append("            } else {\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("              alert('Houve uma falha de sistema ao tentar realizar esta operação.');\n");
//            } else {
//                builder.append("              alert('There was a system crash while trying to perform this operation.');\n");
//            }
//            builder.append("            }\n");
//            builder.append("          }\n");
//            builder.append("        );\n");
//            builder.append("      }\n");
//            builder.append("      function refresh() {\n");
//            builder.append("        filterText = document.getElementById('filterField').value;\n");
//            builder.append("        $.post(\n");
//            builder.append("          '");
//            builder.append(Core.getURL());
//            builder.append(user.getEmail());
//            builder.append("',\n");
//            builder.append("          {filter:filterText},\n");
//            builder.append("          function(data, status) {\n");
//            builder.append("            if (status == 'success') {\n");
//            builder.append("              $('#tableBody').html(data);\n");
//            builder.append("              view($('#tableBody tr').attr('id'));\n");
//            builder.append("            } else {\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("              alert('Houve uma falha de sistema ao tentar realizar esta operação.');\n");
//            } else {
//                builder.append("              alert('There was a system crash while trying to perform this operation.');\n");
//            }
//            builder.append("            }\n");
//            builder.append("          }\n");
//            builder.append("        );\n");
//            builder.append("      }\n");
//            builder.append("    </script>\n");
//            builder.append("  </head>\n");
//            // Body.
//            builder.append("  <body>\n");
//            builder.append("    <div class=\"header\">\n");
//            if (queryKeySet.isEmpty()) {
//                builder.append("      <iframe id=\"viewer\" src=\"about:blank\"></iframe>\n");
//            } else {
//                builder.append("      <iframe id=\"viewer\" src=\"");
//                builder.append(Core.getURL());
//                builder.append(queryKeySet.last());
//                builder.append("\"></iframe>\n");
//            }
//            // Construção da tabela de consultas.
//            builder.append("      <table>\n");
//            builder.append("        <thead>\n");
//            builder.append("          <tr>\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("            <th style=\"width:120px;\">Recepção</th>\n");
//                builder.append("            <th>Origem</th>\n");
//                builder.append("            <th>Remetente</th>\n");
//                builder.append("            <th>Conteúdo</th>\n");
//                builder.append("            <th>Entrega</th>\n");
//            } else {
//                builder.append("            <th style=\"width:160px;\">Reception</th>\n");
//                builder.append("            <th style=\"width:auto;\">Source</th>\n");
//                builder.append("            <th style=\"width:auto;\">Sender</th>\n");
//                builder.append("            <th style=\"width:auto;\">Content</th>\n");
//                builder.append("            <th style=\"width:auto;\">Delivery</th>\n");
//            }
//            builder.append("          </tr>\n");
//            builder.append("        </thead>\n");
//            builder.append("      </table>\n");
//            builder.append("    </div>\n");
//            if (queryKeySet.isEmpty()) {
//                builder.append("    <table>\n");
//                builder.append("      <tbody>\n");
//                builder.append("        <tr>\n");
//                if (locale.getLanguage().toLowerCase().equals("pt")) {
//                    builder.append("          <td colspan=\"5\" align=\"center\">nenhum registro encontrado</td>\n");
//                } else {
//                    builder.append("          <td colspan=\"5\" align=\"center\">no records found</td>\n");
//                }
//                builder.append("        </tr>\n");
//                builder.append("      </tbody>\n");
//                builder.append("    </table>\n");
//            } else {
//                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
//                GregorianCalendar calendar = new GregorianCalendar();
//                Long nextQuery = null;
//                while (queryKeySet.size() > User.QUERY_MAX_ROWS) {
//                    nextQuery = queryKeySet.pollFirst();
//                }
//                builder.append("    <table>\n");
//                builder.append("      <tbody id=\"tableBody\">\n");
//                for (Long time : queryKeySet.descendingSet()) {
//                    User.Query query = user.getQuery(time);
//                    boolean highlight = time.equals(queryKeySet.last());
//                    buildQueryRow(locale, builder, dateFormat, calendar, time, query, highlight);
//                }
//                if (nextQuery == null) {
//                    builder.append("      <tr>\n");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("        <td colspan=\"5\" align=\"center\">não foram encontrados outros registros</td>\n");
//                    } else {
//                        builder.append("        <td colspan=\"5\" align=\"center\">no more records found</td>\n");
//                    }
//                    builder.append("      </tr>\n");
//                } else {
//                    builder.append("        <tr id=\"rowMore\" class=\"click\" onclick=\"more('");
//                    builder.append(nextQuery);
//                    builder.append("')\">\n");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">clique para ver mais registros</td>\n");
//                    } else {
//                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">click to see more records</td>\n");
//                    }
//                    builder.append("        </tr>\n");
//                }
//                builder.append("      </tbody>\n");
//                builder.append("    </table>\n");
//            }
//            builder.append("    <div class=\"bottom\">\n");
//            builder.append("      <table>\n");
//            builder.append("        <tr>\n");
//            if (locale.getLanguage().toLowerCase().equals("pt")) {
//                builder.append("          <th>Pesquisar <input type=\"text\" id=\"filterField\" name=\"filterField\" onkeydown=\"if (event.keyCode == 13) refresh();\" autofocus></th>\n");
//            } else {
//                builder.append("          <th>Search <input type=\"text\" id=\"filterField\" name=\"filterField\" onkeydown=\"if (event.keyCode == 13) refresh();\" autofocus></th>\n");
//            }
//            builder.append("          <th style=\"text-align:right;\"><small>");
//            builder.append("Powered by <a target=\"_blank\" href=\"http://spfbl.net/\" style=\"color: #b4b9d2;\">SPFBL.net</a></small>");
//            builder.append("</th>\n");
//            builder.append("        </tr>\n");
//            builder.append("      <table>\n");
//            builder.append("    </div>\n");
//            builder.append("  </body>\n");
//            builder.append("</html>\n");
//        } else {
//            TreeSet<Long> queryKeySet = user.getQueryKeySet(begin, filter);
//            if (queryKeySet.isEmpty()) {
//                builder.append("        <tr>\n");
//                if (locale.getLanguage().toLowerCase().equals("pt")) {
//                    builder.append("          <td colspan=\"5\" align=\"center\">nenhum registro encontrado</td>\n");
//                } else {
//                    builder.append("          <td colspan=\"5\" align=\"center\">no records found</td>\n");
//                }
//                builder.append("        </tr>\n");
//            } else {
//                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
//                GregorianCalendar calendar = new GregorianCalendar();
//                Long nextQuery = null;
//                while (queryKeySet.size() > User.QUERY_MAX_ROWS) {
//                    nextQuery = queryKeySet.pollFirst();
//                }
//                for (Long time : queryKeySet.descendingSet()) {
//                    User.Query query = user.getQuery(time);
//                    buildQueryRow(locale, builder, dateFormat, calendar, time, query, false);
//                }
//                if (nextQuery == null) {
//                    builder.append("        <tr>\n");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("          <td colspan=\"5\" align=\"center\">não foram encontrados outros registros</td>\n");
//                    } else {
//                        builder.append("          <td colspan=\"5\" align=\"center\">no more records found</td>\n");
//                    }
//                    builder.append("        </tr>\n");
//                } else {
//                    builder.append("        <tr id=\"rowMore\" class=\"click\" onclick=\"more('");
//                    builder.append(nextQuery);
//                    builder.append("')\">\n");
//                    if (locale.getLanguage().toLowerCase().equals("pt")) {
//                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">clique para ver mais registros</td>\n");
//                    } else {
//                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">click to see more records</td>\n");
//                    }
//                    builder.append("        </tr>\n");
//                }
//            }
//        }
//        return builder.toString();
//    }
    
    private static String getControlPanel(
            Locale locale,
            User user,
            Long begin,
            String filter
            ) {
        StringBuilder builder = new StringBuilder();
        if (begin == null && filter == null) {
//            builder.append("<!DOCTYPE html>\n");
            builder.append("<html lang=\"");
            builder.append(locale.getLanguage());
            builder.append("\">\n");
            builder.append("  <head>\n");
            builder.append("    <meta charset=\"UTF-8\">\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("    <title>Painel de controle do SPFBL</title>\n");
            } else {
                builder.append("    <title>SPFBL control panel</title>\n");
            }
            // Styled page.
            builder.append("    <style type=\"text/css\">\n");
            builder.append("      body {\n");
            builder.append("        margin:180px 0px 30px 0px;\n");
            builder.append("        background:lightgray;\n");
            builder.append("      }\n");
            builder.append("      iframe {\n");
            builder.append("        border-width: 0px 0px 0px 0px;\n");
            builder.append("        width:100%;\n");
            builder.append("        height:150px;\n");
            builder.append("      }\n");
            builder.append("      .header {\n");
            builder.append("        background-color:lightgray;\n");
            builder.append("        border-width: 0px 0px 0px 0px;\n");
            builder.append("        position:fixed;\n");
            builder.append("        top:0px;\n");
            builder.append("        margin:auto;\n");
            builder.append("        z-index:1;\n");
            builder.append("        width:100%;\n");
            builder.append("        height:180px;\n");
            builder.append("      }\n");
            builder.append("      .bottom {\n");
            builder.append("        background-color:lightgray;\n");
            builder.append("        border-width: 0px 0px 0px 0px;\n");
            builder.append("        position:fixed;\n");
            builder.append("        bottom:0px;\n");
            builder.append("        margin:auto;\n");
            builder.append("        z-index:1;\n");
            builder.append("        width:100%;\n");
            builder.append("        height:30px;\n");
            builder.append("      }\n");
            builder.append("      .button {\n");
            builder.append("          background-color: #4CAF50;\n");
            builder.append("          border: none;\n");
            builder.append("          color: white;\n");
            builder.append("          padding: 16px 32px;\n");
            builder.append("          text-align: center;\n");
            builder.append("          text-decoration: none;\n");
            builder.append("          display: inline-block;\n");
            builder.append("          font-size: 16px;\n");
            builder.append("          margin: 4px 2px;\n");
            builder.append("          -webkit-transition-duration: 0.4s;\n");
            builder.append("          transition-duration: 0.4s;\n");
            builder.append("          cursor: pointer;\n");
            builder.append("      }\n");
            builder.append("      .sender {\n");
            builder.append("          background-color: white; \n");
            builder.append("          color: black; \n");
            builder.append("          border: 2px solid #008CBA;\n");
            builder.append("          width: 100%;\n");
            builder.append("          word-wrap: break-word;\n");
            builder.append("      }\n");
            builder.append("      .sender:hover {\n");
            builder.append("          background-color: #008CBA;\n");
            builder.append("          color: white;\n");
            builder.append("      }\n");
            builder.append("      .highlight {\n");
            builder.append("        background: #b4b9d2;\n");
            builder.append("        color:black;\n");
            builder.append("        border-top: 1px solid #22262e;\n");
            builder.append("        border-bottom: 1px solid #22262e;\n");
            builder.append("      }\n");
            builder.append("      .highlight:nth-child(odd) td {\n");
            builder.append("        background: #b4b9d2;\n");
            builder.append("      }\n");
            builder.append("      .click {\n");
            builder.append("        cursor:pointer;\n");
            builder.append("        cursor:hand;\n");
            builder.append("      }\n");
            builder.append("      table {\n");
            builder.append("        background: white;\n");
            builder.append("        table-layout:fixed;\n");
            builder.append("        border-collapse: collapse;\n");
            builder.append("        word-wrap:break-word;\n");
            builder.append("        border-radius:3px;\n");
            builder.append("        border-collapse: collapse;\n");
            builder.append("        margin: auto;\n");
            builder.append("        padding:2px;\n");
            builder.append("        width: 100%;\n");
            builder.append("        box-shadow: 0 5px 10px rgba(0, 0, 0, 0.1);\n");
            builder.append("        animation: float 5s infinite;\n");
            builder.append("      }\n");
            builder.append("      th {\n");
            builder.append("        color:#FFFFFF;;\n");
            builder.append("        background:#1b1e24;\n");
            builder.append("        border-bottom:4px solid #9ea7af;\n");
            builder.append("        border-right: 0px;\n");
            builder.append("        font-size:16px;\n");
            builder.append("        font-weight: bold;\n");
            builder.append("        padding:4px;\n");
            builder.append("        text-align:left;\n");
            builder.append("        text-shadow: 0 1px 1px rgba(0, 0, 0, 0.1);\n");
            builder.append("        vertical-align:middle;\n");
            builder.append("        height:30px;\n");
            builder.append("      }\n");
            builder.append("      tr {\n");
            builder.append("        border-top: 1px solid #C1C3D1;\n");
            builder.append("        border-bottom-: 1px solid #C1C3D1;\n");
            builder.append("        font-size:16px;\n");
            builder.append("        font-weight:normal;\n");
            builder.append("        text-shadow: 0 1px 1px rgba(256, 256, 256, 0.1);\n");
            builder.append("      }\n");
            builder.append("      tr:nth-child(odd) td {\n");
            builder.append("        background:#EBEBEB;\n");
            builder.append("      }\n");
            builder.append("      td {\n");
            builder.append("        padding:2px;\n");
            builder.append("        vertical-align:middle;\n");
            builder.append("        font-size:16px;\n");
            builder.append("        text-shadow: -1px -1px 1px rgba(0, 0, 0, 0.1);\n");
            builder.append("        border-right: 1px solid #C1C3D1;\n");
            builder.append("      }\n");
            builder.append("      input[type=text], select {\n");
            builder.append("        width: 400px;\n");
            builder.append("        padding: 0px 4px;\n");
            builder.append("        margin: 1px 0;\n");
            builder.append("        display: inline-block;\n");
            builder.append("        background: #b4b9d2;\n");
            builder.append("        border: 1px solid #ccc;\n");
            builder.append("        border-radius: 4px;\n");
            builder.append("        box-sizing: border-box;\n");
            builder.append("      }\n");
            builder.append("    </style>\n");
            // JavaScript functions.
            TreeMap<Long,Query> queryMap = user.getQueryMap(null, null);
            builder.append("    <script type=\"text/javascript\" src=\"https://ajax.googleapis.com/ajax/libs/jquery/2.1.3/jquery.min.js\"></script>\n");
            builder.append("    <script type=\"text/javascript\">\n");
            builder.append("      window.onbeforeunload = function () {\n");
            builder.append("        document.getElementById('filterField').value = '';\n");
            builder.append("        window.scrollTo(0, 0);\n");
            builder.append("      }\n");
            builder.append("      var last = ");
            if (queryMap.isEmpty()) {
                builder.append(0);
            } else {
                builder.append(queryMap.lastKey());
            }
            builder.append(";\n");
            builder.append("      var filterText = '';\n");
            builder.append("      function view(query) {\n");
            builder.append("        if (query == undefined || query == 0) {\n");
            builder.append("          var viewer = document.getElementById('viewer');\n");
            builder.append("          viewer.src = 'about:blank';\n");
            builder.append("          last = 0;\n");
            builder.append("        } else if (last != query) {\n");
            builder.append("          var viewer = document.getElementById('viewer');\n");
            builder.append("          viewer.addEventListener('load', function() {\n");
            builder.append("            if (document.getElementById(last)) {\n");
            builder.append("              document.getElementById(last).className = 'tr';\n");
            builder.append("              document.getElementById(last).className = 'click';\n");
            builder.append("            }\n");
            builder.append("            document.getElementById(query).className = 'highlight';\n");
            builder.append("            last = query;\n");
            builder.append("          });\n");
            builder.append("          viewer.src = '");
            builder.append(Core.getURL());
            builder.append("' + query;\n");
            builder.append("        }\n");
            builder.append("      }\n");
            builder.append("      function more(query) {\n");
            builder.append("        var rowMore = document.getElementById('rowMore');\n");
            builder.append("        rowMore.onclick = '';\n");
            builder.append("        rowMore.className = 'tr';\n");
            builder.append("        var columnMore = document.getElementById('columnMore');\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("        columnMore.innerHTML = 'carregando mais registros';\n");
            } else {
                builder.append("        columnMore.innerHTML = 'loading more records';\n");
            }
            builder.append("        $.post(\n");
            builder.append("          '");
            builder.append(Core.getURL());
            builder.append(user.getEmail());
            builder.append("',\n");
            builder.append("          {filter:filterText,begin:query},\n");
            builder.append("          function(data, status) {\n");
            builder.append("            if (status == 'success') {\n");
            builder.append("              rowMore.parentNode.removeChild(rowMore);\n");
            builder.append("              $('#tableBody').append(data);\n");
            builder.append("            } else {\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("              alert('Houve uma falha de sistema ao tentar realizar esta operação.');\n");
            } else {
                builder.append("              alert('There was a system crash while trying to perform this operation.');\n");
            }
            builder.append("            }\n");
            builder.append("          }\n");
            builder.append("        );\n");
            builder.append("      }\n");
            builder.append("      function refresh() {\n");
            builder.append("        filterText = document.getElementById('filterField').value;\n");
            builder.append("        $.post(\n");
            builder.append("          '");
            builder.append(Core.getURL());
            builder.append(user.getEmail());
            builder.append("',\n");
            builder.append("          {filter:filterText},\n");
            builder.append("          function(data, status) {\n");
            builder.append("            if (status == 'success') {\n");
            builder.append("              $('#tableBody').html(data);\n");
            builder.append("              view($('#tableBody tr').attr('id'));\n");
            builder.append("            } else {\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("              alert('Houve uma falha de sistema ao tentar realizar esta operação.');\n");
            } else {
                builder.append("              alert('There was a system crash while trying to perform this operation.');\n");
            }
            builder.append("            }\n");
            builder.append("          }\n");
            builder.append("        );\n");
            builder.append("      }\n");
            builder.append("    </script>\n");
            builder.append("  </head>\n");
            // Body.
            builder.append("  <body>\n");
            builder.append("    <div class=\"header\">\n");
            if (queryMap.isEmpty()) {
                builder.append("      <iframe id=\"viewer\" src=\"about:blank\"></iframe>\n");
            } else {
                builder.append("      <iframe id=\"viewer\" src=\"");
                builder.append(Core.getURL());
                builder.append(queryMap.lastKey());
                builder.append("\"></iframe>\n");
            }
            // Construção da tabela de consultas.
            builder.append("      <table>\n");
            builder.append("        <thead>\n");
            builder.append("          <tr>\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("            <th style=\"width:120px;\">Recepção</th>\n");
                builder.append("            <th>Origem</th>\n");
                builder.append("            <th>Remetente</th>\n");
                builder.append("            <th>Conteúdo</th>\n");
                builder.append("            <th>Entrega</th>\n");
            } else {
                builder.append("            <th style=\"width:160px;\">Reception</th>\n");
                builder.append("            <th style=\"width:auto;\">Source</th>\n");
                builder.append("            <th style=\"width:auto;\">Sender</th>\n");
                builder.append("            <th style=\"width:auto;\">Content</th>\n");
                builder.append("            <th style=\"width:auto;\">Delivery</th>\n");
            }
            builder.append("          </tr>\n");
            builder.append("        </thead>\n");
            builder.append("      </table>\n");
            builder.append("    </div>\n");
            if (queryMap.isEmpty()) {
                builder.append("    <table>\n");
                builder.append("      <tbody>\n");
                builder.append("        <tr>\n");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("          <td colspan=\"5\" align=\"center\">nenhum registro encontrado</td>\n");
                } else {
                    builder.append("          <td colspan=\"5\" align=\"center\">no records found</td>\n");
                }
                builder.append("        </tr>\n");
                builder.append("      </tbody>\n");
                builder.append("    </table>\n");
            } else {
                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, user.getLocale());
                GregorianCalendar calendar = new GregorianCalendar();
                Long nextQuery = null;
                while (queryMap.size() > User.QUERY_MAX_ROWS) {
                    nextQuery = queryMap.pollFirstEntry().getKey();
                }
                builder.append("    <table>\n");
                builder.append("      <tbody id=\"tableBody\">\n");
                for (Long time : queryMap.descendingKeySet()) {
                    User.Query query = queryMap.get(time);
                    boolean highlight = time.equals(queryMap.lastKey());
                    buildQueryRow(locale, builder, dateFormat, calendar, time, query, highlight);
                }
                if (nextQuery == null) {
                    builder.append("      <tr>\n");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("        <td colspan=\"5\" align=\"center\">não foram encontrados outros registros</td>\n");
                    } else {
                        builder.append("        <td colspan=\"5\" align=\"center\">no more records found</td>\n");
                    }
                    builder.append("      </tr>\n");
                } else {
                    builder.append("        <tr id=\"rowMore\" class=\"click\" onclick=\"more('");
                    builder.append(nextQuery);
                    builder.append("')\">\n");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">clique para ver mais registros</td>\n");
                    } else {
                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">click to see more records</td>\n");
                    }
                    builder.append("        </tr>\n");
                }
                builder.append("      </tbody>\n");
                builder.append("    </table>\n");
            }
            builder.append("    <div class=\"bottom\">\n");
            builder.append("      <table>\n");
            builder.append("        <tr>\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("          <th>Pesquisar <input type=\"text\" id=\"filterField\" name=\"filterField\" onkeydown=\"if (event.keyCode == 13) refresh();\" autofocus></th>\n");
            } else {
                builder.append("          <th>Search <input type=\"text\" id=\"filterField\" name=\"filterField\" onkeydown=\"if (event.keyCode == 13) refresh();\" autofocus></th>\n");
            }
            builder.append("          <th style=\"text-align:right;\"><small>");
            builder.append("Powered by <a target=\"_blank\" href=\"http://spfbl.net/\" style=\"color: #b4b9d2;\">SPFBL.net</a></small>");
            builder.append("</th>\n");
            builder.append("        </tr>\n");
            builder.append("      <table>\n");
            builder.append("    </div>\n");
            builder.append("  </body>\n");
            builder.append("</html>\n");
        } else {
            TreeMap<Long,Query> queryMap = user.getQueryMap(begin, filter);
            if (queryMap.isEmpty()) {
                builder.append("        <tr>\n");
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    builder.append("          <td colspan=\"5\" align=\"center\">nenhum registro encontrado</td>\n");
                } else {
                    builder.append("          <td colspan=\"5\" align=\"center\">no records found</td>\n");
                }
                builder.append("        </tr>\n");
            } else {
                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, user.getLocale());
                GregorianCalendar calendar = new GregorianCalendar();
                Long nextQuery = null;
                while (queryMap.size() > User.QUERY_MAX_ROWS) {
                    nextQuery = queryMap.pollFirstEntry().getKey();
                }
                for (Long time : queryMap.descendingKeySet()) {
                    User.Query query = queryMap.get(time);
                    buildQueryRow(locale, builder, dateFormat, calendar, time, query, false);
                }
                if (nextQuery == null) {
                    builder.append("        <tr>\n");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("          <td colspan=\"5\" align=\"center\">não foram encontrados outros registros</td>\n");
                    } else {
                        builder.append("          <td colspan=\"5\" align=\"center\">no more records found</td>\n");
                    }
                    builder.append("        </tr>\n");
                } else {
                    builder.append("        <tr id=\"rowMore\" class=\"click\" onclick=\"more('");
                    builder.append(nextQuery);
                    builder.append("')\">\n");
                    if (locale.getLanguage().toLowerCase().equals("pt")) {
                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">clique para ver mais registros</td>\n");
                    } else {
                        builder.append("          <td id=\"columnMore\" colspan=\"5\" align=\"center\">click to see more records</td>\n");
                    }
                    builder.append("        </tr>\n");
                }
            }
        }
        return builder.toString();
    }
    
    public static boolean loadStyleCSS(
            StringBuilder builder
    ) {
        File styleFile = getWebFile("style.css");
        if (styleFile == null) {
            return false;
        } else {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(styleFile));
                try {
                    builder.append("    <style>\n");
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append("      ");
                        builder.append(line);
                        builder.append('\n');
                    }
                    builder.append("    </style>\n");
                } finally {
                    reader.close();
                }
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
    
    private static void buildHead(
            StringBuilder builder,
            String title
    ) {
        builder.append("  <head>\n");
        builder.append("    <meta charset=\"UTF-8\">\n");
        builder.append("    <link rel=\"shortcut icon\" type=\"image/png\" href=\"favicon.png\">\n");
        builder.append("    <title>");
        builder.append(title);
        builder.append("</title>\n");
        builder.append("    <link rel=\"stylesheet\" href=\"style.css\">\n");
        if (Core.hasRecaptchaKeys()) {
//             novo reCAPCHA
//            builder.append("    <script src=\"https://www.google.com/recaptcha/api.js\" async defer></script>\n");
        }
        builder.append("  </head>\n");
    }
    
    private static void buildHead(
            StringBuilder builder,
            String title,
            String page,
            int time
    ) {
        builder.append("  <head>\n");
        builder.append("    <meta charset=\"UTF-8\">\n");
        builder.append("    <link rel=\"shortcut icon\" type=\"image/png\" href=\"favicon.png\">\n");
        builder.append("    <meta charset=\"UTF-8\" http-equiv=\"refresh\" content=\"");
        builder.append(time);
        builder.append(";url=");
        builder.append(page);
        builder.append("\">\n");
        if (title != null) {
            builder.append("    <title>");
            builder.append(title);
            builder.append("</title>\n");
        }
        builder.append("    <link rel=\"stylesheet\" href=\"style.css\">\n");
        if (Core.hasRecaptchaKeys()) {
//             novo reCAPCHA
//            builder.append("    <script src=\"https://www.google.com/recaptcha/api.js\" async defer></script>\n");
        }
        builder.append("  </head>\n");
    }
    
    private static void buildLogo(
            StringBuilder builder
    ) {
        builder.append("      <div id=\"divlogo\">\n");
        builder.append("        <img src=\"logo.png\" alt=\"Logo\" style=\"max-width:468px;max-height:60px;\">\n");
        builder.append("      </div>\n");
    }
    
    public static void buildMessage(
            StringBuilder builder,
            String message
    ) {
        builder.append("      <hr>\n");
        builder.append("      <div id=\"divmsg\">\n");
        builder.append("        <p id=\"titulo\">");
        builder.append(message);
        builder.append("</p>\n");
        builder.append("      </div>\n");
    }
    
    public static void buildText(
            StringBuilder builder,
            String message
    ) {
        builder.append("      <div id=\"divtexto\">\n");
        StringTokenizer tokenizer = new StringTokenizer(message, "\n");
        while (tokenizer.hasMoreTokens()) {
            String line = tokenizer.nextToken();
            builder.append("        <p>");
            builder.append(line);
            builder.append("</p>\n");
        }
        builder.append("      </div>\n");
    }
    
    public static void buildConfirmAction(
            StringBuilder builder,
            String name,
            String url,
            String description,
            String publisher,
            String website
    ) {
        builder.append("    <script type=\"application/ld+json\">\n");
        builder.append("    {\n");
        builder.append("      \"@context\": \"http://schema.org\",\n");
        builder.append("      \"@type\": \"EmailMessage\",\n");
        builder.append("      \"potentialAction\": {\n");
        builder.append("        \"@type\": \"ViewAction\",\n");
        builder.append("        \"target\": \"" + url + "\",\n");
        builder.append("        \"url\": \"" + url + "\",\n");
        builder.append("        \"name\": \"" + name + "\"\n");
        builder.append("      },\n");
        builder.append("      \"description\": \"" + description + "\",\n");
        builder.append("      \"publisher\": {\n");
        builder.append("        \"@type\": \"Organization\",\n");
        builder.append("        \"name\": \"" + publisher + "\",\n");
        builder.append("        \"url\": \"" + website + "\"\n");
        builder.append("      }\n");
        builder.append("    }\n");
        builder.append("    </script>\n");
    }
    
//    public static void buildFooter(
//            StringBuilder builder,
//            Locale locale
//    ) {
//        builder.append("      <hr>\n");
//        builder.append("      <div id=\"divfooter\">\n");
//        if (locale.getLanguage().toLowerCase().equals("pt")) {
//            builder.append("        <div id=\"divanuncio\">\n");
//            builder.append("          Anuncie aqui pelo <a target=\"_blank\" href='http://a-ads.com?partner=455818'>Anonymous Ads</a>\n");
//            builder.append("        </div>\n");
//            builder.append("        <div id=\"divpowered\">\n");
//            builder.append("          Powered by <a target=\"_blank\" href=\"http://spfbl.net/\">SPFBL.net</a>\n");
//            builder.append("        </div>\n");
//        } else {
//            builder.append("        <div id=\"divanuncio\">\n");
//            builder.append("          Advertise here by <a target=\"_blank\" href='http://a-ads.com?partner=455818'>Anonymous Ads</a>\n");
//            builder.append("        </div>\n");
//            builder.append("        <div id=\"divpowered\">\n");
//            builder.append("          Powered by <a target=\"_blank\" href=\"http://spfbl.net/\">SPFBL.net</a>\n");
//            builder.append("        </div>\n");
//         }
//        builder.append("      </div>\n");
//    }
    
    public static void buildFooter(
            StringBuilder builder,
            Locale locale,
            String unsubscribeURL
    ) {
        builder.append("      <hr>\n");
        builder.append("      <div id=\"divfooter\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            if (unsubscribeURL == null) {
                builder.append("        <div id=\"divanuncio\">\n");
                builder.append("          Anuncie aqui pelo <a target=\"_blank\" href='http://a-ads.com?partner=455818'>Anonymous Ads</a>\n");
                builder.append("        </div>\n");
            } else {
                builder.append("        <div id=\"divanuncio\">\n");
                builder.append("          <a target=\"_blank\" href='");
                builder.append(unsubscribeURL);
                builder.append("'>Cancelar inscrição</a>\n");
                builder.append("        </div>\n");
            }
            builder.append("        <div id=\"divpowered\">\n");
            builder.append("          Powered by <a target=\"_blank\" href=\"http://spfbl.net/\">SPFBL.net</a>\n");
            builder.append("        </div>\n");
        } else {
            if (unsubscribeURL == null) {
                builder.append("        <div id=\"divanuncio\">\n");
                builder.append("          Advertise here by <a target=\"_blank\" href='http://a-ads.com?partner=455818'>Anonymous Ads</a>\n");
                builder.append("        </div>\n");
            } else {
                builder.append("        <div id=\"divanuncio\">\n");
                builder.append("          <a target=\"_blank\" href='");
                builder.append(unsubscribeURL);
                builder.append("'>Unsubscribe</a>\n");
                builder.append("        </div>\n");
            }
            builder.append("        <div id=\"divpowered\">\n");
            builder.append("          Powered by <a target=\"_blank\" href=\"http://spfbl.net/\">SPFBL.net</a>\n");
            builder.append("        </div>\n");
         }
        builder.append("      </div>\n");
    }
   
    private static String getMainHTML(
            Locale locale,
            String message,
            String value
            ) {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Serviço SPFBL");
        } else {
            buildHead(builder, "SPFBL Service");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        if (Core.hasPortDNSBL()) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Para consultar uma reputação no serviço DNSBL, digite um IP ou um domínio:");
            } else {
                buildText(builder, "To query for reputation in the DNSBL service, type an IP or domain:");
            }
            builder.append("      <div id=\"divcaptcha\">\n");
            builder.append("        <form method=\"POST\">\n");
            builder.append("          <input type=\"text\" name=\"query\" value=\"");
            builder.append(value);
            builder.append("\" autofocus><br>\n");
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("          <input id=\"btngo\" type=\"submit\" value=\"Consultar\">\n");
            } else {
                builder.append("          <input id=\"btngo\" type=\"submit\" value=\"Query\">\n");
            }
            builder.append("        </form>\n");
            builder.append("      </div>\n");
        } else {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Nenhuma ferramenta está disponível neste momento.");
            } else {
                buildText(builder, "No tool is available at this time.");
            }
        }
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }

    private static String getComplainHMTL(
            Locale locale,
            TreeSet<String> tokenSet,
            TreeSet<String> selectionSet,
            String message,
            boolean whiteBlockForm
            ) throws ProcessException {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html>\n");
        builder.append("<html lang=\"");
        builder.append(locale.getLanguage());
        builder.append("\">\n");
        if (locale.getLanguage().toLowerCase().equals("pt")) {
            buildHead(builder, "Página de denuncia SPFBL");
        } else {
            buildHead(builder, "SPFBL complaint page");
        }
        builder.append("  <body>\n");
        builder.append("    <div id=\"container\">\n");
        buildLogo(builder);
        buildMessage(builder, message);
        if (whiteBlockForm) {
            writeBlockFormHTML(locale, builder, tokenSet, selectionSet);
        }
        buildFooter(builder, locale, null);
        builder.append("    </div>\n");
        builder.append("  </body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }

    private static void writeBlockFormHTML(
            Locale locale,
            StringBuilder builder,
            TreeSet<String> tokenSet,
            TreeSet<String> selectionSet
            ) throws ProcessException {
        if (!tokenSet.isEmpty()) {
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                buildText(builder, "Se você deseja não receber mais mensagens desta origem no futuro, selecione os identificadores que devem ser bloqueados definitivamente:");
            } else {
                buildText(builder, "If you want to stop receiving messages from the source in the future, select identifiers that should definitely be blocked:");
            }
            builder.append("    <form method=\"POST\">\n");
            for (String identifier : tokenSet) {
                builder.append("        <input type=\"checkbox\" name=\"identifier\" value=\"");
                builder.append(identifier);
                if (selectionSet.contains(identifier)) {
                    builder.append("\" checked>");
                } else {
                    builder.append("\">");
                }
                builder.append(identifier);
                builder.append("<br>\n");
            }
            if (Core.hasRecaptchaKeys()) {
                if (locale.getLanguage().toLowerCase().equals("pt")) {
                    buildText(builder, "Para que sua solicitação seja aceita, resolva o desafio reCAPTCHA abaixo.");
                } else {
                    buildText(builder, "For your request to be accepted, please solve the reCAPTCHA below.");
                }
            }
            builder.append("      <div id=\"divcaptcha\">\n");
            if (Core.hasRecaptchaKeys()) {
                String recaptchaKeySite = Core.getRecaptchaKeySite();
                String recaptchaKeySecret = Core.getRecaptchaKeySecret();
                ReCaptcha captcha = ReCaptchaFactory.newReCaptcha(recaptchaKeySite, recaptchaKeySecret, false);
                builder.append("      ");
                builder.append(captcha.createRecaptchaHtml(null, null).replace("\r", ""));
                // novo reCAPCHA
    //            builder.append("      <div class=\"g-recaptcha\" data-sitekey=\"");
    //            builder.append(recaptchaKeySite);
    //            builder.append("\"></div>\n");
            }
            if (locale.getLanguage().toLowerCase().equals("pt")) {
                builder.append("        <input id=\"btngo\" type=\"submit\" value=\"Bloquear\">\n");
            } else {
                builder.append("        <input id=\"btngo\" type=\"submit\" value=\"Block\">\n");
            }
            builder.append("      </div>\n");
            builder.append("    </form>\n");
        }
    }

    private static void response(int code, String response,
            HttpExchange exchange) throws IOException {
        byte[] byteArray = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(code, byteArray.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(byteArray);
        } finally {
            os.close();
        }
    }

    /**
     * Inicialização do serviço.
     */
    @Override
    public void run() {
        SERVER.start();
        Server.logInfo("listening on HTTP port " + PORT + ".");
    }

    @Override
    protected void close() throws Exception {
        Server.logDebug("unbinding HTTP on port " + PORT + "...");
        SERVER.stop(1);
        Server.logInfo("HTTP server closed.");
    }
}
