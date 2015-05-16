package pl.edu.agh.toik.apv.crawler;

import com.google.common.collect.Sets;
import org.apache.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import pl.edu.agh.toik.visualisation.database.dao.OfferDAO;
import pl.edu.agh.toik.visualisation.database.dto.Offer;
import pl.edu.agh.toik.visualisation.database.dto.enums.OfferType;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Created by Magda on 2015-05-06.
 */
public class HomeBrokerCrawler {

	private static Logger LOG = Logger.getLogger(HomeBrokerCrawler.class);

	private static final String RENT_START_URL = "https://homebroker.pl/wynajmij";

	private static final String SELL_START_URL = "https://homebroker.pl/kup";

	private static String outputFile;

	private Set<String> visitedUrls = new HashSet<String>();

	private Set<String> urlsToVisit = new HashSet<String>();

	private FirefoxDriver driver = new FirefoxDriver();

	private int maxNumberOfEntries = 100;

	private int visitedUrlsCount = 0;

	private List<Offer> offers = new ArrayList<Offer>();

	private Set<String> validUrlElements = Sets.newHashSet("oferty", "wynajmij", "kup", "wyniki-wyszukiwania");

	private Set<String> allowedDomains = Sets.newHashSet("https://homebroker.pl/");

    private static String stringURI = "postgres://yjgigsqewjnjwr:J7plfUUZwc-c5TUDdo5sm-GHwA@ec2-54-217-202-108.eu-west-1.compute.amazonaws.com:5432/dau4v1agvs3tmr";

    private static Connection getConnection() throws URISyntaxException, SQLException {
        URI dbUri = new URI(stringURI);

        LOG.info(dbUri);
        String username = dbUri.getUserInfo().split(":")[0];
        String password = dbUri.getUserInfo().split(":")[1];
        String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + ':' + dbUri.getPort() + dbUri.getPath()+"?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";

        return DriverManager.getConnection(dbUrl, username, password);
    }

    Connection connection;

    public void setConnection(Connection connection){
        this.connection = connection;
    }
    /*@Autowired
    OfferDAO offerDAO;*/

	public static void main(String[] args) {

        Connection connection = null;
        HomeBrokerCrawler homeBrokerCrawler = new HomeBrokerCrawler();
        try {
            connection = getConnection();
            homeBrokerCrawler.setConnection(connection);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        System.out.println("Starting");


//		homeBrokerCrawler.processPage(RENT_START_URL);
//		homeBrokerCrawler.processPage("https://homebroker.pl/?t=9#!r=wyniki-wyszukiwania:typ,mieszkanie|rynek,pierwotny-wtorny|rub,sprzedaz|metraz,|lokalizacja,Krolewska-10-20400-Lublin-Polska|standard,%20|pokoje_od,0|pokoje_do,0|cena_od,|cena_do,|pietro_od,|pietro_do,|rok_budowy_od,|rok_budowy_do,undefined|dystans,1000");

		// for testing
		homeBrokerCrawler.processPage("https://homebroker.pl/oferty/mieszkanie-rynek-wtorny-wynajem-mazowieckie-warszawa-srodmiescie-bagno-oferta-328788.html");
	}

	private void processPage(String url) {
		LOG.info("Enter: processPage: " + url);

		if ( !hasBeenVisited(url) && isAllowedUrl(url) ) {
			Document document = null;
			try {
				// TODO: add retries and checking if downloaded data is valid (no 404 pages)
				driver.manage().timeouts().implicitlyWait(30, TimeUnit.SECONDS);
				driver.get(url);
				String htmlContent = driver.getPageSource();

				document = Jsoup.parse(htmlContent);
			} catch ( Exception e ) {
				LOG.error(e.getMessage());
			}

			if (document != null) {
				LOG.info("Document downloaded properly: " + url);

				if ( isOfferUrl(url) ) {
					extractData(document, url);
				}

				visitedUrls.add(url);

//				waitAfterRequests();

				followHyperLinks(document);
			} else {
				LOG.info("Document null: " + url);
			}
		}

//		if ( success ) {
//			visitedUrlsCount++;
//		}
	}

	private void extractData(Document document, String url) {
		LOG.info("Enter: extractData");

		Offer offer = new Offer();

		Elements inputs = document.select(".offer-top-info input[type=hidden]");
		Elements mapDivs = document.select("#map3");

		if ( inputs.isEmpty() || mapDivs.isEmpty() ) {
			return; // no offer on this page
		}

		for ( Element input : inputs ) {
			String id = input.id();
			String value = input.val();
			HomeBrokerOfferField homeBrokerOfferField = HomeBrokerOfferField.getInstanceForId(id);
			if ( homeBrokerOfferField != null ) {
				homeBrokerOfferField.fillOfferField(offer, value);
			}
		}

		for ( Element div : mapDivs ) {
			offer.setLatitude(Double.parseDouble(div.attr("data-lat")));
			offer.setLongitude(Double.parseDouble(div.attr("data-lng")));
		}

		setOfferType(offer, url);
		saveIfNotEmpty(offer);

		LOG.info("Obtained offer: " + offer.toString());
		System.out.println(offer);
	}

	private void followHyperLinks(Document document) {
		LOG.info("Enter: followHyperLinks");
		Elements links = document.select("a[href]");
		for ( Element link : links ) {
//			System.out.println(link.attr("abs:href"));
			// TODO: uncomment me
			processPage(link.attr("abs:href"));
		}
	}

	private void setOfferType(Offer offer, String url) {
		for ( String key : HomeBrokerOfferConstants.OFFER_TYPES_MAP.keySet() ) {
			if ( url.contains(key) ) {
				offer.setOfferType(HomeBrokerOfferConstants.OFFER_TYPES_MAP.get(key));
			}
		}
	}

	private boolean isAllowedUrl(String url) {
		if ( url.endsWith("#") || url.endsWith(".pdf") || url.endsWith(".jpg")) {
			return false;
		}

		boolean isValidDomain = false;
		for ( String domain : allowedDomains ) {
			if ( url.contains(domain) ) {
				isValidDomain = true;
				break;
			}
		}

		return isValidDomain;
	}

	private boolean hasBeenVisited(String url) {
		return visitedUrls.contains(url);
	}

	private boolean isOfferUrl(String url) {
		for ( String key : HomeBrokerOfferConstants.OFFER_TYPES_MAP.keySet() ) {
			if ( url.contains(key) ) {
				return true;
			}
		}
		return false;
	}

    private static String insertQuery = "INSERT INTO \"OFFER\" (\"OFFER_ID\",\"TYPE\",\"CITY\",\"PRICE\",\"AREA\",\"LATITUDE\",\"LONGITUDE\") VALUES( ? , ? , ? , ? , ? , ? , ? )";

    private void saveIfNotEmpty(Offer offer) {
		boolean isEmpty = offer.getCity() == null || offer.getPrice() == 0.0 || offer.getLatitude() == 0.0 || offer.getLongitude() == 0.0;
		if ( !isEmpty ) {
            if( ( "Krakow".equals(offer.getCity())||"Kraków".equals(offer.getCity()) ) && OfferType.RENT.equals(offer.getOfferType()) ){
                LOG.info("trying to save the offer");
                try {
                    PreparedStatement statement = connection.prepareStatement(insertQuery);
                    statement.setLong(1,offer.getOfferId());
                    statement.setString(2,offer.getType());
                    statement.setString(3,offer.getCity());
                    statement.setDouble(4,offer.getPrice());
                    statement.setDouble(5,offer.getArea());
                    statement.setDouble(6,offer.getLatitude());
                    statement.setDouble(7,offer.getLongitude());
                    statement.executeUpdate();
                    connection.commit();
                } catch (SQLException e) {
                    e.printStackTrace();
                }

                //offerDAO.saveOffer(offer);
            }
		}
	}

	private void waitAfterRequests() {
		if ( visitedUrlsCount == 10 ) {
			try {
				Thread.sleep(10000);
				visitedUrlsCount = 0;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
