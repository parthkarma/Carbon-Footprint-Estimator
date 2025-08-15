# Carbon Footprint Estimator API

A Spring Boot REST API that estimates the carbon footprint of dishes using OpenAI's GPT models. The service can analyze dish names or food images to identify ingredients and calculate their estimated CO₂ emissions.

## Features

- **Dish Name Analysis**: Input a dish name to get carbon footprint estimation
- **Image Analysis**: Upload food images for automatic dish recognition and carbon estimation
- **Intelligent Rate Limiting**: Built-in rate limiting for OpenAI API calls
- **Robust Error Handling**: Comprehensive retry mechanisms and fallback responses
- **Caching**: Image-based request caching to reduce API calls
- **Docker Support**: Containerized deployment ready

## Tech Stack

- **Backend**: Spring Boot 3.x, Spring WebFlux
- **AI Integration**: OpenAI GPT-4 and GPT-4 Vision models
- **Build Tool**: Maven
- **Containerization**: Docker
- **Validation**: Jakarta Bean Validation

## How to Run the Project

### Prerequisites

- Java 17+
- Maven 3.6+
- OpenAI API Key
- Docker (optional)

### Local Development

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd carbon-footprint-estimator
   ```

2. **Configure OpenAI API**
   
   Create `application.properties` or set environment variables:
   ```properties
   openai.base-url=https://api.openai.com/v1
   openai.api-key=your-openai-api-key-here
   openai.model=gpt-4o-mini
   openai.vision-model=gpt-4o-mini
   
   # Rate limiting configuration
   openai.rate-limit.enabled=true
   openai.rate-limit.min-interval-ms=20000
   ```

3. **Run the application**
   ```bash
   mvn spring-boot:run
   ```

   The API will be available at `http://localhost:8080`

### Docker Deployment

1. **Build Docker image**
   ```bash
   docker build -t carbon-footprint-api .
   ```

2. **Run container**
   ```bash
   docker run -p 8080:8080 \
     -e OPENAI_API_KEY=your-api-key \
     -e OPENAI_BASE_URL=https://api.openai.com/v1 \
     carbon-footprint-api
   ```

## API Endpoints

### 1. Estimate from Dish Name

**Endpoint**: `POST /api/estimate`

**Request Body**:
```json
{
  "dish": "Chicken Fried Rice"
}
```

**Response**:
```json
{
  "dish": "Chicken Fried Rice",
  "estimatedCarbonKg": 4.8,
  "ingredients": [
    {
      "name": "Rice",
      "carbonKg": 1.1
    },
    {
      "name": "Chicken",
      "carbonKg": 2.5
    },
    {
      "name": "Oil",
      "carbonKg": 0.4
    },
    {
      "name": "Vegetables",
      "carbonKg": 0.1
    },
    {
      "name": "Spices",
      "carbonKg": 0.2
    }
  ],
  "error": null
}
```

### 2. Estimate from Image

**Endpoint**: `POST /api/estimate/image`

**Content-Type**: `multipart/form-data`

**Form Data**:
- `file`: Image file (JPEG, PNG, etc.)

**Response**: Same format as dish name estimation

## Example Usage

### Postman Collection

🚀 **Ready-to-use API testing**: [Carbon Footprint Estimator API Workspace](https://www.postman.com/material-physicist-22014399/workspace/carbon-footprint-estimator-api)

The Postman workspace includes:
- Pre-configured requests for both endpoints
- Sample request bodies
- Environment variables for easy testing
- Real response examples

### Manual Testing Examples

**Dish Name Estimation**:
```bash
curl -X POST http://localhost:8080/api/estimate \
  -H "Content-Type: application/json" \
  -d '{"dish": "Margherita Pizza"}'
```

**Image Upload**:
```bash
curl -X POST http://localhost:8080/api/estimate/image \
  -F "file=@/path/to/food-image.jpg"
```

## Key Design Decisions

### 1. **Reactive Architecture**
- Used Spring WebFlux with `WebClient` for non-blocking I/O
- Improves scalability when dealing with external API calls
- Better resource utilization during OpenAI API latency

### 2. **Rate Limiting Strategy**
- Implemented semaphore-based rate limiting for OpenAI free tier
- Configurable minimum intervals between requests (default 20 seconds)
- Prevents API quota exhaustion and 429 errors

### 3. **Robust Error Handling**
- Exponential backoff retry mechanism with max 5 attempts
- Graceful degradation with fallback responses
- Detailed error logging for debugging

### 4. **Multi-Model AI Integration**
- Separate models for text (GPT-4O-Mini) and vision tasks
- Flexible model configuration via properties
- Cost optimization by using appropriate model for each task

### 5. **Carbon Footprint Database**
- Static in-memory database for quick lookups
- Covers common ingredients with realistic CO₂ values
- Easy to extend with more ingredients

### 6. **Caching Strategy**
- Simple hash-based caching for image analysis
- Reduces duplicate OpenAI API calls for same images
- Memory-based for simplicity (can be extended to Redis)

## Assumptions and Limitations

### Assumptions
- Carbon values are per typical serving size
- Ingredient identification is simplified (doesn't account for cooking methods)
- Free tier OpenAI usage with conservative rate limits
- Images contain recognizable food items

### Current Limitations
- **Static Carbon Database**: Limited to ~20 common ingredients
- **Simplified Calculations**: Doesn't account for cooking methods, transportation, packaging
- **Rate Limiting**: Conservative limits may cause delays
- **Memory Caching**: Cache doesn't persist across application restarts
- **Single Language**: Only supports English dish names
- **No Authentication**: API is open without access controls

## Production Considerations

### 1. **Scalability & Performance**
- **Database Integration**: Replace static map with proper database (PostgreSQL/MongoDB)
- **Distributed Caching**: Implement Redis for cross-instance caching
- **Load Balancing**: Add multiple instances behind load balancer
- **Connection Pooling**: Configure proper HTTP client connection pools

### 2. **Security & Authentication**
- **API Authentication**: Implement JWT or API key authentication
- **Rate Limiting**: Add per-user rate limiting
- **Input Validation**: Enhanced validation for file uploads (size, type, malware scanning)
- **HTTPS**: Enforce SSL/TLS in production
- **Secret Management**: Use environment variables or secret management tools

### 3. **Monitoring & Observability**
- **Metrics**: Add Prometheus metrics for API performance
- **Health Checks**: Implement proper health endpoints
- **Distributed Tracing**: Add Jaeger/Zipkin for request tracing
- **Centralized Logging**: ELK stack or cloud logging solutions
- **Alerting**: Set up alerts for high error rates or API failures

### 4. **Data & AI Improvements**
- **Comprehensive Database**: Integrate with real carbon footprint databases
- **ML Pipeline**: Consider training custom models for better ingredient recognition
- **Data Validation**: Implement confidence scores for AI predictions
- **A/B Testing**: Framework for testing different AI models

### 5. **Infrastructure & DevOps**
- **CI/CD Pipeline**: Automated testing and deployment
- **Environment Management**: Separate dev/staging/prod configurations
- **Container Orchestration**: Kubernetes deployment
- **Backup & Recovery**: Database backup strategies
- **Auto-scaling**: Horizontal pod autoscaling based on load

### 6. **Business Logic Enhancements**
- **Regional Variations**: Support different carbon coefficients by region
- **Portion Sizes**: Allow users to specify serving sizes
- **Recipe Analysis**: Support for full recipes with quantities
- **Historical Tracking**: Store user's carbon footprint history
- **Recommendations**: Suggest lower-carbon alternatives

### 7. **Compliance & Documentation**
- **API Documentation**: OpenAPI/Swagger documentation
- **Data Privacy**: GDPR compliance for image processing
- **Terms of Service**: Clear usage terms and limitations
- **SLA Definition**: Define and monitor service level agreements

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

[Add your license information here]

---

**Note**: This is a prototype implementation. Carbon footprint values are approximations and should not be used for official environmental reporting.
